-- ============================================================
-- AI Gateway schema (PostgreSQL). Dev 环境由 ConnectionFactoryInitializer 自动执行。
-- 生产建议改用 Flyway/Liquibase 管理。所有语句幂等(IF NOT EXISTS / ON CONFLICT)。
-- 部署在专用库 ai-gateway（独立实例），不与既有业务库共用，无表名冲突风险。
-- ============================================================

-- ---------- 表1：客户端/员工身份 ----------
CREATE TABLE IF NOT EXISTS api_client (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_key_id   VARCHAR(64)  NOT NULL UNIQUE,            -- 网关 Key 的可见标识(前缀)
    api_key_hash    VARCHAR(128) NOT NULL,                   -- 网关下发 Key 的 SHA-256(不存明文)
    employee_id     VARCHAR(64),                             -- 关联员工工号
    display_name    VARCHAR(128),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    rate_limit_qps  INT,
    role            VARCHAR(16)  NOT NULL DEFAULT 'EMPLOYEE',-- EMPLOYEE/LEADER/ADMIN：pm-agent 据此定可见范围
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 老库幂等补列（CREATE IF NOT EXISTS 不会给既有表加列）
ALTER TABLE api_client ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'EMPLOYEE';
-- 每月成本额度(人民币元)。NULL = 不限额；超额后在对话末尾给该员工告警(不强制拦截)。
ALTER TABLE api_client ADD COLUMN IF NOT EXISTS monthly_quota_cny NUMERIC(12,2);

-- ---------- 表2：会话主日志(微调语料核心) ----------
CREATE TABLE IF NOT EXISTS chat_log (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id            UUID         NOT NULL UNIQUE,       -- 网关侧请求标识, 同时回写响应头
    upstream_request_id   VARCHAR(64),                        -- 百炼返回的 request_id, 用于对账
    trace_id              VARCHAR(32),                        -- 关联 OTLP 链路
    span_id               VARCHAR(16),

    client_id             BIGINT,                             -- 用户/客户端ID (REFERENCES api_client.id)
    employee_id           VARCHAR(64),                        -- 冗余, 便于按员工导出语料

    model_requested       VARCHAR(64)  NOT NULL,              -- 客户端请求的模型名
    model_upstream        VARCHAR(64)  NOT NULL,              -- 实际路由到的百炼模型
    is_stream             BOOLEAN      NOT NULL,

    request_messages      JSONB        NOT NULL,              -- 完整 Prompt: 原始 messages 数组
    request_params        JSONB,                              -- temperature/top_p 等参数快照
    completion_content    TEXT,                               -- 完整 Completion: 拼接后的文本
    completion_role       VARCHAR(16)  DEFAULT 'assistant',
    tool_calls            JSONB,                              -- 函数调用(按 index 归并后)
    finish_reason         VARCHAR(32),

    prompt_tokens_est     INT,                                -- Token 消耗预估: 入
    completion_tokens_est INT,                                -- 出
    total_tokens_est      INT,
    token_source          VARCHAR(16)  NOT NULL DEFAULT 'ESTIMATED', -- UPSTREAM / ESTIMATED

    status                VARCHAR(16)  NOT NULL,              -- SUCCESS / PARTIAL / ERROR
    http_status           INT,
    error_type            VARCHAR(64),
    error_message         TEXT,

    ttft_ms               INT,                                -- 首字延迟(流式)
    duration_ms           INT          NOT NULL,              -- 请求总耗时
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_chatlog_client_time ON chat_log (client_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chatlog_time        ON chat_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chatlog_model_time  ON chat_log (model_upstream, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chatlog_abnormal    ON chat_log (status) WHERE status <> 'SUCCESS';

-- ---------- 表3：每日 Token 用量聚合(报表/计费) ----------
CREATE TABLE IF NOT EXISTS token_usage_daily (
    stat_date          DATE         NOT NULL,
    client_id          BIGINT       NOT NULL,
    model_upstream     VARCHAR(64)  NOT NULL,
    request_count      BIGINT       NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT       NOT NULL DEFAULT 0,
    completion_tokens  BIGINT       NOT NULL DEFAULT 0,
    total_tokens       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_date, client_id, model_upstream)
);

-- ---------- 表3.5：模型价目（token → 人民币换算，按 model_upstream 计价） ----------
-- 同一张表可并存百炼(qwen*)与 DeepSeek 等不同厂商模型，各按自己的单价算钱。
-- 输入/输出分开计价；成本 = Σ(prompt_tokens×input_price + completion_tokens×output_price) / 1000。
CREATE TABLE IF NOT EXISTS model_pricing (
    model_upstream        VARCHAR(64)   PRIMARY KEY,           -- 与 chat_log/token_usage_daily 的 model_upstream 对齐
    input_price_per_1k    NUMERIC(12,6) NOT NULL DEFAULT 0,    -- 每 1000 个输入(prompt) token 单价
    output_price_per_1k   NUMERIC(12,6) NOT NULL DEFAULT 0,    -- 每 1000 个输出(completion) token 单价
    currency              VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    effective_date        DATE          NOT NULL DEFAULT CURRENT_DATE,
    note                  VARCHAR(256)
);
-- 种子：统一费率(适配 Token Plan)。保留行 '__default__' = 兜底单价：
--   所有「没有单独定价」的模型都按它计 → 改这一行即改全局费率、新模型自动覆盖、无需逐个登记。
-- 预填值取 qwen3.7-max 按量价（输入 ¥12 / 输出 ¥36 每百万 token = 每千 0.012 / 0.036）作为起点；
--   若你按 Token Plan 套餐折算出别的单价，UPDATE 这一行即可。
-- 需要对某个模型精确计价时，再 INSERT 该 model_upstream 的专属行（专属行会覆盖兜底行）。
INSERT INTO model_pricing (model_upstream, input_price_per_1k, output_price_per_1k, note) VALUES
    ('__default__', 0.012, 0.036, '统一/兜底单价：未单独定价的模型按此计；改它即改全局费率')
ON CONFLICT (model_upstream) DO NOTHING;

-- ---------- 视图：每员工本月用量与成本（管理员直查 / pm-agent 取数口径一致） ----------
-- 用法：SELECT * FROM v_employee_monthly_cost;  调额度：UPDATE api_client SET monthly_quota_cny=200 WHERE employee_id='…';
-- 注：cost_cny 依赖 model_pricing 单价；未配价的模型按 0 计。
CREATE OR REPLACE VIEW v_employee_monthly_cost AS
SELECT c.employee_id,
       c.display_name,
       c.monthly_quota_cny,
       COALESCE(SUM(d.request_count), 0) AS requests,
       COALESCE(SUM(d.total_tokens), 0)  AS total_tokens,
       ROUND(COALESCE(SUM(
           (d.prompt_tokens     * COALESCE(p.input_price_per_1k,  def.input_price_per_1k,  0)
          + d.completion_tokens * COALESCE(p.output_price_per_1k, def.output_price_per_1k, 0)) / 1000.0
       ), 0), 2) AS cost_cny
FROM api_client c
LEFT JOIN token_usage_daily d
       ON d.client_id = c.id
      AND d.stat_date >= date_trunc('month', CURRENT_DATE)::date
LEFT JOIN model_pricing p   ON p.model_upstream   = d.model_upstream
LEFT JOIN model_pricing def ON def.model_upstream = '__default__'
WHERE c.employee_id IS NOT NULL
GROUP BY c.employee_id, c.display_name, c.monthly_quota_cny
ORDER BY cost_cny DESC;

-- ============================================================
-- 虚拟员工模块 (digital worker)：PM 数字员工的产出表。
-- 这两张表只被 com.company.digitalworker 读写；网关链路不依赖它们。
-- ⚠️ 敏感数据：summary_text 由员工 AI 对话归纳而来，生产务必做表级访问控制 + 应用层加密，
--    并明确告知员工/符合公司合规。
-- ============================================================

-- ---------- 表4：个人工作摘要 (Pass① 产出，一员工一周期一行) ----------
CREATE TABLE IF NOT EXISTS work_summary (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id           VARCHAR(64)  NOT NULL,              -- 员工工号 (取自 chat_log.employee_id)
    client_id             BIGINT,                             -- 关联 api_client.id (冗余便于追溯)
    period_type           VARCHAR(8)   NOT NULL,              -- DAILY / WEEKLY (MVP 仅 DAILY)
    period_start          TIMESTAMPTZ  NOT NULL,              -- 分析窗口 [start, end)
    period_end            TIMESTAMPTZ  NOT NULL,
    conversation_count    INT          NOT NULL DEFAULT 0,    -- 本期纳入分析的对话条数
    summary_text          TEXT,                               -- LLM 总结：这个人这段时间在做什么
    key_tasks             JSONB,                              -- 结构化要点 (任务/产出/卡点列表)
    model_used            VARCHAR(64),
    prompt_tokens         INT,
    completion_tokens     INT,
    status                VARCHAR(8)   NOT NULL DEFAULT 'OK', -- OK / EMPTY (无对话) / ERROR
    error_message         TEXT,
    generated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 同一员工同一窗口幂等：重跑覆盖而非堆积
    CONSTRAINT uq_work_summary UNIQUE (employee_id, period_type, period_start)
);

CREATE INDEX IF NOT EXISTS idx_work_summary_period   ON work_summary (period_type, period_start DESC);
CREATE INDEX IF NOT EXISTS idx_work_summary_employee ON work_summary (employee_id, period_start DESC);

-- ---------- 表5：项目进度 (Pass② 二级聚合，按项目/主题归类) ----------
CREATE TABLE IF NOT EXISTS project_progress (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_type           VARCHAR(8)   NOT NULL,
    period_start          TIMESTAMPTZ  NOT NULL,
    period_end            TIMESTAMPTZ  NOT NULL,
    project_key           VARCHAR(128),                       -- 归一化项目标识 (LLM 给出)
    project_name          VARCHAR(256) NOT NULL,              -- 项目/主题名 (LLM 从对话识别)
    summary_text          TEXT,                               -- 该项目本期进展
    contributors          JSONB,                              -- 参与员工列表 [{employee_id, role?}]
    status_signal         VARCHAR(16),                        -- 顺利 / 有阻塞 / 停滞 (可选)
    source_summary_ids    JSONB,                              -- 由哪些 work_summary.id 汇总而来
    model_used            VARCHAR(64),
    generated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_progress_period ON project_progress (period_type, period_start DESC);
