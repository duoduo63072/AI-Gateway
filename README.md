# AI Gateway

企业级 AI 网关：将员工本地 **Claude Code** 等客户端发出的 **Anthropic Messages API** 报文，
**透传**到**阿里百炼 Token Plan MaaS（Anthropic 端点）**，在**不阻塞 SSE 流式响应**的前提下，
**异步采集完整对话语料**（prompt + 最终回答 + token 用量），用于后续训练公司专属“数字员工”。

- 协议：**Anthropic 透传**——客户端与上游同讲 Anthropic 协议，网关**不做协议翻译**，只注入上游密钥、做模型名映射、并旁路采集语料。
- 框架：Spring Boot 3.3 WebFlux + WebClient（全链路响应式，应对高并发 SSE）
- 存储：R2DBC + PostgreSQL（**外部实例**，如阿里云 RDS；存会话语料 + 每日 Token 用量）
- 可观测性：OpenTelemetry (OTLP)，遵循 GenAI 语义约定（本地默认**不导出**，见下）
- 部署：多阶段 Dockerfile + docker-compose（网关 + OTel Collector；数据库用外部 PostgreSQL）

> **演进说明**：网关最初按 OpenAI 兼容模式设计（`/v1/chat/completions` → 百炼 compatible-mode）。
> 现已改造为 **Anthropic 透传**，让 Claude Code 直接经网关走（带语料采集），而不再绕过网关。
> OpenAI 兼容入口与相关类（`ChatHandler` / `ChatProxyService` / `StreamAccumulator`）**休眠保留**以备回退。

## 架构与数据流

```
Claude Code (Anthropic Messages API)
   │  POST /v1/messages   (Bearer 网关Key, anthropic-version 头)
   ▼
[ApiKeyAuthFilter] ──校验──> [MessagesHandler] ──> [MessagesProxyService]
                                                       │  注入百炼密钥(Authorization) / claude→qwen 模型映射 / 透传 anthropic-version、anthropic-beta
                                                       ▼
                                                 [WebClient] ───SSE──> 百炼 MaaS /apps/anthropic/v1/messages
                                                       │
       即收即转给客户端 ◀── doOnNext 旁路累积(AnthropicStreamAccumulator) ──┐
                                                       │                     │
                                                 doFinally 触发               │
                                                       ▼                     │
                                     [CapturePersistenceService] ──异步(boundedElastic)──> PostgreSQL
```

流式核心：`doOnNext` 旁路解析 Anthropic SSE 事件、拼接最终回答，`doFinally` 异步落库，**主转发链路零阻塞**；
客户端中断 / 上游异常时仍落库并标记 `PARTIAL` / `ERROR`。

**采集口径**：`completion_content` 仅存**最终文本回答**（`text` 块拼接）；`thinking`/推理块**不入库**；
`tool_use` 以 **Anthropic 原生结构**存入 `tool_calls`；token 取上游 `usage`（`message_delta` 末帧会复报更全的 `input_tokens`，以其为准）。

## 目录结构

```
src/main/java/com/company/aigateway/
├── AiGatewayApplication.java
├── config/        # 配置属性 + WebClient + R2DBC 初始化
├── security/      # API Key 鉴权 (WebFilter + Service)
├── web/           # RouterFunction + Handler
│   ├── ChatRouter.java          # /v1/messages、/v1/messages/count_tokens、/v1/chat/completions(休眠)
│   ├── MessagesHandler.java     # ★ Anthropic 入口：取身份、校验、透传 anthropic-* 头
│   └── ChatHandler.java         # OpenAI 入口（休眠保留）
├── proxy/         # 代理核心
│   ├── MessagesProxyService.java        # ★ Anthropic 透传 + 旁路采集 + count_tokens 纯透传
│   ├── AnthropicStreamAccumulator.java  # ★ 解析 Anthropic SSE 事件，拼最终文本/tool_use/usage
│   ├── RequestContext.java              # 单次请求可变上下文：计时、trace/span、错误状态
│   ├── ChatProxyService.java            # OpenAI 代理（休眠保留）
│   └── StreamAccumulator.java           # OpenAI SSE 累加器（休眠保留）
├── token/         # TokenEstimator (jtokkit 兜底估算)
└── persistence/   # 实体 / Repository / 异步落库服务

src/main/java/com/company/digitalworker/   # ★ 虚拟员工模块（与网关隔离，单向依赖；详见“虚拟员工模块”节）
├── config/        # DigitalWorkerProperties（开关/cron/模型/窗口）+ @EnableScheduling（gated）
├── source/        # ConversationReader：只读 chat_log，裁剪成分析原料
├── analyzer/      # LlmAnalyzer（非流式调百炼）+ PromptBuilder + AnalysisResult（解析/降级）
├── summary/       # WorkSummaryService 编排两段 + 实体 / Repository
├── schedule/      # SummaryScheduler：@Scheduled 定时触发
├── agent/         # ★ pm-agent：PmAgentHandler(网关SPI;真流式/非流式) + PmAgentService(工具循环 runLoop + 流式 runLoopStream) + AgentLlmClient(call/callStream) + AgentStreamDecoder(流式SSE解析) + PmDataService(按工号取数) + RosterService(花名册) + AgentScope
└── web/           # /api/worker/** 报告路由 + Handler（仅主密钥）
（网关侧新增 com/company/aigateway/agent/AgentHandler.java —— 数字员工接入的扩展点接口）
src/main/resources/
├── application.yml
└── schema.sql     # 5 张表（网关3 + 虚拟员工2）+ 索引
```

## 前置条件

- 一个对**百炼 MaaS Anthropic 端点**有效的 API Key（`DASHSCOPE_API_KEY`，通过环境变量或密钥管理注入）
- 一个可达的 **PostgreSQL**（如阿里云 RDS；连接信息见 `docs/onboarding/environment-guide.md`）
- Docker / Docker Compose（推荐），或本地 JDK 17 + Maven

## 快速开始（Docker Compose）

```bash
cp .env.example .env
# 编辑 .env：填入 DB_* 连接信息、DASHSCOPE_API_KEY，按需设 GATEWAY_MASTER_KEY
docker compose up --build
```

启动后网关监听 `http://localhost:8080`，OTel Collector `4317/4318`。数据库为**外部 PostgreSQL**（不在 compose 内）。
首次连接时 `schema.sql` 幂等建表。仓库不内置任何可用的网关 Key；本地联调可在 `.env` 设置 `GATEWAY_MASTER_KEY`，或从私有备份恢复 `seed-employees.sql`。

> compose 内置 OTel Collector，故 `OTLP_METRICS_EXPORT=true`、`OTEL_SAMPLING=1.0`（全采样导出）；
> 本地裸跑无 collector，二者默认关闭，避免每 10s 连 `:4318` 失败刷屏。

## 本地运行（不走 Docker）

```bash
# 需先有一个可达的 PostgreSQL，并把连接信息写入 .env
set -a; . ./.env; set +a
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q clean package -DskipTests
JAVA_HOME=$(/usr/libexec/java_home -v 17) "$JAVA_HOME/bin/java" -jar target/ai-gateway-0.1.0.jar
```

## 让 Claude Code 走网关

把 Claude Code 的上游指向网关即可（网关替它注入真实百炼密钥）。在 `~/.claude/settings.json`：

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://localhost:8080",
    "ANTHROPIC_AUTH_TOKEN": "<gateway-key>"
  }
}
```

- `ANTHROPIC_AUTH_TOKEN` 填**网关下发的 Key**（或 `GATEWAY_MASTER_KEY` 引导密钥），**不是**真实百炼密钥——员工拿不到上游密钥。
- Claude Code 会把请求发到 `${ANTHROPIC_BASE_URL}/v1/messages`，由网关透传到百炼并旁路采集语料。

## API 用法

网关暴露 Anthropic Messages 端点。客户端按 Anthropic 协议发报文，鉴权用网关下发的 Key。

**流式（SSE）：**

```bash
curl -N http://localhost:8080/v1/messages \
  -H "Authorization: Bearer <gateway-key>" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "claude-3-5-sonnet-20241022",
        "max_tokens": 256,
        "stream": true,
        "messages": [{"role":"user","content":"用一句话介绍你自己"}]
      }'
```

**非流式：** 同上去掉 `"stream": true`。

**count_tokens（纯透传，不采集）：**

```bash
curl http://localhost:8080/v1/messages/count_tokens \
  -H "Authorization: Bearer <gateway-key>" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-3-5-sonnet-20241022","messages":[{"role":"user","content":"hi"}]}'
```

模型名按 `gateway.model-mapping` 映射：标准 Claude 名（如 `claude-3-5-sonnet-20241022 → qwen3.7-max`、
`claude-3-5-haiku-20241022 → qwen3.6-flash`）；以 `qwen` 开头者**直接透传**；未命中走 `default-model`（`qwen3.7-max`）。

## 关键配置（application.yml / 环境变量）

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| 百炼地址 | `DASHSCOPE_BASE_URL` | `https://token-plan.cn-beijing.maas.aliyuncs.com` | 上游基址（仅 scheme+host） |
| Messages 路径 | `DASHSCOPE_MESSAGES_PATH` | `/apps/anthropic/v1/messages` | Anthropic 报文端点 |
| count_tokens 路径 | `DASHSCOPE_COUNT_TOKENS_PATH` | `/apps/anthropic/v1/messages/count_tokens` | 量算端点 |
| anthropic 版本 | `ANTHROPIC_VERSION` | `2023-06-01` | 客户端未带 `anthropic-version` 头时的兜底 |
| 百炼密钥 | `DASHSCOPE_API_KEY` | — | **必填**，服务端注入上游 `Authorization` |
| 默认上游模型 | `DEFAULT_UPSTREAM_MODEL` | `qwen3.7-max` | 未命中映射时使用 |
| 网关主密钥 | `GATEWAY_MASTER_KEY` | 空 | 可选引导密钥；留空仅用 DB Key |
| 鉴权开关 | `GATEWAY_AUTH_ENABLED` | `true` | |
| 采集开关 | `GATEWAY_CAPTURE_ENABLED` | `true` | 关闭则不落库 |
| OTLP traces | `OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | |
| OTLP metrics | `OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | |
| OTLP metrics 导出开关 | `OTLP_METRICS_EXPORT` | `false` | 本地无 collector 默认关；compose 内置 collector 置 `true` |
| Trace 采样率 | `OTEL_SAMPLING` | `0.0` | 本地默认不采样（保留 Tracer Bean）；compose 置 `1.0` 全采样 |

## 数据表

- `api_client`：客户端/员工身份，API Key 以 SHA-256 存储。
- `chat_log`：会话主日志（完整 messages、最终回答、token 用量、耗时、状态、trace_id 等），微调语料从此表导出。
- `token_usage_daily`：按天 × 客户端 × 模型聚合的 Token 用量。
- `work_summary` / `project_progress`：**虚拟员工模块**产出（个人工作摘要 / 项目进度），详见下节。

导出一条微调样本 = `request_messages` 数组追加 `{role:"assistant", content: completion_content}`。
注意 `completion_content` 仅含**最终文本**（不含 thinking）；如对话用了工具，`tool_calls` 另存 Anthropic 原生 `tool_use`。

## 虚拟员工模块（PM 数字员工）

在网关之外、同一进程内的一个**离线分析管道**：读 `chat_log`（员工用 Claude Code 的对话语料），
调百炼上游做总结，产出**「每个人在做什么」+「各项目进展」**，替代项目例会与周报给领导看。

> **与网关的隔离**：独立顶层包 `com.company.digitalworker`，单向依赖——网关侧不 import 它。
> 它只读 `chat_log`、调百炼**不经** `MessagesProxyService`（故分析内容**不会污染语料库**、不递归）。
> 两边唯一接缝就是 `chat_log` 这张表。默认 `DIGITAL_WORKER_ENABLED=false`，不开启时定时器与报告接口都不注册。

**数据流（两段式）**：

```
@Scheduled(每日02:00 跑前一天 + 工作日10–18点每小时跑当天)  ──>  读 chat_log（按员工×时间窗，只取 SUCCESS）
   Pass①  每个员工 → 调百炼 → 个人工作摘要         ──> work_summary
   Pass②  汇总当期所有摘要 → 调百炼按项目/主题归类  ──> project_progress
   报告接口  GET /api/worker/summaries | /projects（仅主密钥）
```

> **新鲜度**：定时分析有两档——每日 02:00 跑前一天（最终定稿）+ **工作日 10:00–18:00 每小时跑当天**（`intraday-cron`，置 `-` 关闭）。
> 此外 pm-agent 提供 `refresh_data` 工具：用户说"更新最新数据"即按需重跑当天分析（带 10 分钟防抖 + 并发锁，避免重复打爆上游）。
> 所有分析经 `runDailyGuarded` 串行化，定时与手动刷新不会叠加。

**开启与试跑**：

```bash
# .env 里打开模块（并确保 DASHSCOPE_API_KEY 可用、GATEWAY_MASTER_KEY 已设）
DIGITAL_WORKER_ENABLED=true
GATEWAY_MASTER_KEY=<local-gateway-master-key>

# 手动触发一次（默认分析昨天；可带 ?date=YYYY-MM-DD），无需等定时
curl -X POST "http://localhost:8080/api/worker/run" \
  -H "Authorization: Bearer <local-gateway-master-key>"

# 看个人摘要 / 项目进度（默认近 7 天；summaries 可带 ?employeeId=EMP-0001）
curl "http://localhost:8080/api/worker/summaries?from=2026-05-01&to=2026-05-30" \
  -H "Authorization: Bearer <local-gateway-master-key>"
curl "http://localhost:8080/api/worker/projects" \
  -H "Authorization: Bearer <local-gateway-master-key>"
```

**关键配置**（`digital-worker.*` / 环境变量，详见 `application.yml`）：

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| 总开关 | `DIGITAL_WORKER_ENABLED` | `false` | 关闭则模块完全休眠（定时器/接口都不注册） |
| 定时开关 | `DIGITAL_WORKER_SCHEDULE_ENABLED` | `true` | 模块开启时，是否自动定时跑；关掉只留手动接口 |
| 每日 cron | `DIGITAL_WORKER_DAILY_CRON` | `0 0 2 * * *` | 凌晨 02:00 跑前一天 |
| 分析模型 | `DIGITAL_WORKER_MODEL` | `qwen3.7-max` | 复用百炼上游，总结质量优先 |
| 单员工对话上限 | `DIGITAL_WORKER_MAX_CONVERSATIONS` | `40` | 控上下文与成本 |
| 单条截断字数 | `DIGITAL_WORKER_MAX_CHARS` | `1200` | 用户输入/回答各自上限 |
| 分析并发 | `DIGITAL_WORKER_CONCURRENCY` | `3` | 逐员工分析的并发上限 |

> ⚠️ **隐私与合规**：`work_summary` / `project_progress` 是由员工 AI 对话归纳出的**工作画像，属敏感数据**。
> 报告接口仅放行**网关主密钥**身份（普通员工 Key 访问返回 403）。生产务必做**表级访问控制 + 应用层加密**，
> 并就“用 AI 对话分析员工工作”与员工/合规达成明确共识。MVP 暂未做逐员工 opt-in 开关，如有需要可在 `api_client` 加列扩展。

## pm-agent（对话式数字项目经理）

在 Claude Code 里把 **model 设为 `pm-agent`**，即可直接“和项目经理对话”——问项目进展、问员工工作。
网关识别到该 model 后**不透传百炼**，转给 `PmAgentHandler`：agent 用工具检索结构化数据（`work_summary`/`project_progress`）
再组织回答，**答案有据可依、不是凭空生成**。pm-agent 对话**不采集进 chat_log**（避免“问 PM”被当成工作）。

```
Claude Code (model=pm-agent, 各自Key)
   │ /v1/messages
   ▼
[MessagesProxyService] 命中 pm-agent → [PmAgentHandler]
   ▼  agent 循环（流式请求时每轮流式调上游）：调百炼(带tools) → tool_use? → 本地按身份取数 → 回灌 → 最终答案逐 token 流式吐回
        工具：query_work_summaries / query_project_progress（按工号在 SQL 层强制权限）、refresh_data（按需重跑当天分析）
```

**身份与权限（核心）**——权限在**数据层用工号过滤强制，不靠提示词**（防话术越权）：

| 角色（api_client.role） | 可见范围 |
|---|---|
| `LEADER` / `ADMIN`（或主密钥） | 全部员工的工作摘要 + 全部项目进度 |
| `EMPLOYEE` | **仅本人**工作摘要 + **本人参与**（contributors 含本人工号）的项目 |

员工问他人时，工具只会查到自己的数据，agent 据此如实回答“无权查看”。

**身份自我认知 + 花名册**：鉴权身份的**姓名 + 工号 + 角色**会注入 systemPrompt，agent 因此能正确回答“我是谁 / 我的工号是多少”；同时把**全员花名册（姓名↔工号）**也注入，让 agent 把用户说的“张鹏”自动换算成工号、并在回答里统一用「姓名（工号）」格式称呼员工。`LEADER` 也可查询本人的 `work_summary`（用自己的工号，数据层零改动）。
> ⚠️ 身份与花名册**仅用于自我介绍/称呼，不参与鉴权**——可见范围仍由数据层按工号强制过滤。花名册当前**全员可见**（链路 `api_client.display_name` → `AuthenticatedClient.displayName` → `AgentScope`；由 `RosterService` 带 TTL 缓存提供）。

已真机验证：能说出姓名/工号、称呼员工带工号、领导可看全部、员工只见自己、跨员工被挡；流式为**逐 token 真流式**（一次问答约 57 帧 `text_delta`，而非整段蹦出）。

**用法示例：**

```bash
# 领导（胡跃明）问全局
curl -N -X POST http://localhost:8080/v1/messages \
  -H "Authorization: Bearer <领导的Key>" -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"pm-agent","max_tokens":1024,"stream":true,
       "messages":[{"role":"user","content":"最近一周各项目进展？谁在推进？"}]}'

# 员工问自己
curl -N -X POST http://localhost:8080/v1/messages \
  -H "Authorization: Bearer <员工自己的Key>" -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"pm-agent","max_tokens":1024,"stream":true,
       "messages":[{"role":"user","content":"我最近在做什么？"}]}'
```

**在 Claude Code 里对话**：Claude Code 的 `/model` 选择器固定为 Default/Sonnet/Opus/Haiku 四档，无法新增一行；
把不常用的 **Opus 档重指向 pm-agent** 即可——在 `~/.claude/settings.json`（或 cc-switch 的「配置 JSON」）设
`"ANTHROPIC_DEFAULT_OPUS_MODEL": "pm-agent"`，其余档位保留编码模型。之后对话里 `/model opus` 问 PM、`/model sonnet` 回去写代码。
> ⚠️ 切忌把所有档位（含 `ANTHROPIC_MODEL` / HAIKU / SONNET）都设成 pm-agent——那样会让普通编码与后台小任务也打到 PM、并触发 Claude Code "model 不存在"告警。
> cc-switch 用来切**身份**（不同员工/领导的 Key），不必为切 PM 单独建供应商。

**Key 与角色发放**：员工/领导网关 Key 的 hash 种子不入库到代码仓库；私有环境可从 Obsidian/密钥库恢复 `seed-employees.sql`。
发卡 = 在私有 seed 中加一行 `INSERT ... ON CONFLICT`；轮换 = 换该行 `api_key_hash`。

**配置**（`digital-worker.agent.*`）：

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| 路由 model 名 | `DIGITAL_WORKER_AGENT_MODELS` | `pm-agent` | 命中这些名字才转 agent（逗号分隔可多个） |
| agent 推理模型 | `DIGITAL_WORKER_AGENT_MODEL` | `qwen3.7-max` | agent 自身调的上游模型 |
| 工具循环上限 | `DIGITAL_WORKER_AGENT_MAX_ITERS` | `4` | 防无限 tool_use |
| 默认回看天数 | `DIGITAL_WORKER_AGENT_LOOKBACK_DAYS` | `7` | 问题未给日期时的默认窗口 |
| 真流式开关 | `DIGITAL_WORKER_AGENT_STREAM_ENABLED` | `true` | 流式请求逐 token 实时吐回；`false` 回退为整段答案合成一段 SSE |

> 局限（L1）：只查**结构化摘要**，故 pm-agent 的回答质量取决于 `work_summary`/`project_progress` 的积累——
> 员工得先用各自 Key 产生对话、每日分析跑过，才有可答的数据。模糊/深挖类问题（语义检索原始对话）属 L2，用 `pgvector` 扩展，后续再加。

## 可观测性

- WebFlux server span 与 WebClient client span 自动埋点并父子串联。
- 代理处给 span 打 GenAI 语义约定属性：`gen_ai.system=dashscope` / `gen_ai.request.model` / `gateway.request_id`。
- 指标：`gen_ai.client.token.usage`（input/output 计数）、`gen_ai.server.time_to_first_token`（TTFT）。
- **脱敏**：prompt/completion 不写入 span，只留元数据；内容审计走 `chat_log`。`trace_id` 落库以打通“日志↔链路”。
- **导出策略**：本地默认 `OTEL_SAMPLING=0.0` 且 `OTLP_METRICS_EXPORT=false`（无后端可发，不刷屏）；
  采集仍照常落 PostgreSQL（语料采集与 OTLP **互相独立**）。需要监控时在 compose / 部署环境开启导出即可。

## 安全注意事项

- 百炼密钥仅经环境变量注入上游 `Authorization`，**绝不入库、不打日志、不下发客户端**。
- 网关 Key 仅存 SHA-256；仓库不保存真实员工 Key、员工身份种子或可用演示 Key。
- `request_messages` / `completion_content` 属敏感语料，生产建议应用层加密 + 表级访问控制。
- 生产请用 Flyway/Liquibase 替代启动期 `schema.sql` 自动执行。
- ⚠️ `.env.example` 当前含明文密钥（历史遗留），存在泄露风险；建议迁移密钥管理（KMS / 云效变量 / K8s Secret）并轮换。
