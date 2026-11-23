# 环境配置指南

> **本文档是环境配置的唯一权威来源。** 修改任何环境相关配置前，请先阅读本文档，修改后同步更新本文档。
>
> 最后更新：2026-05-29

---

## 一、阿里云资源总览

### RDS PostgreSQL 实例

| 实例 ID | 用途 | 内网地址 | 外网地址 |
|---------|------|----------|----------|
| `pgm-uf6k6r076121nyq2` | **测试环境** | `pgm-uf6k6r076121nyq2.pg.rds.aliyuncs.com` | `pgm-uf6k6r076121nyq2do.pg.rds.aliyuncs.com` |


- 数据库名：`ai-gateway`
- 用户名：`honyar`
- 密码：走密钥注入，勿写文档

### Redis 实例

| 实例 ID | 用途 | 内网地址 | 外网地址 |
|---------|------|----------|----------|
| `r-uf6jj46f6jp5a1nwqs` | **测试环境** | `r-uf6jj46f6jp5a1nwqs.redis.rds.aliyuncs.com` | `r-uf6jj46f6jp5a1nwqspd.redis.rds.aliyuncs.com` |

- 端口：`6379`
- 数据库：`5`


### ECS 服务器（测试环境）

| 项目 | 值 |
|------|-----|
| VPC ID | `vpc-uf6j9k9zyn23r343zoh55` |
| 内网 IP | `172.19.161.72` |
| 公网出口 | **无**（无 EIP、无 NAT 网关） |


### 百炼模型服务

| 项目 | 值 |
|------|-----|
| 模型服务地址 | `https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic` |
| 模型服务密钥 | 通过 `DASHSCOPE_API_KEY` 从私有 `.env`、CI 变量或密钥管理系统注入 |

---

## 二、AI 网关服务环境变量

网关服务通过**环境变量**读取所有外部依赖；`src/main/resources/application.yml` 中均为占位符
（`${VAR:默认值}`），本地开发用默认值，部署时由 `.env` 文件或容器环境注入真实值。

### 数据库（外部 PostgreSQL）

> 数据层使用 **PostgreSQL**（响应式驱动 `org.postgresql:r2dbc-postgresql`，保持非阻塞）。
> 连接串模板：`r2dbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`

| 环境变量 | application.yml 占位符 | 默认值(本地) | 测试环境填写 |
|----------|------------------------|--------------|--------------|
| `DB_HOST` | `${DB_HOST:localhost}` | `localhost` | 见上方 RDS 地址（ECS 在 VPC 内→优先**内网**地址） |
| `DB_PORT` | `${DB_PORT:5432}` | `5432` | `5432` |
| `DB_NAME` | `${DB_NAME:ai_gateway}` | `ai_gateway` | `ai-gateway` |
| `DB_USER` | `${DB_USER:gateway}` | `gateway` | `honyar` |
| `DB_PASSWORD` | `${DB_PASSWORD:gateway}` | `gateway` | 走密钥注入，勿写文档 |

- 表结构见 `src/main/resources/schema.sql`（PostgreSQL 方言：`UUID` / `JSONB` / `TIMESTAMPTZ` /
  `GENERATED ALWAYS AS IDENTITY` / 对异常状态的部分索引 / `ON CONFLICT`）。
  启动时由 `ConnectionFactoryInitializer` 幂等执行；生产建议改用 Flyway/Liquibase。
- ✅ 已使用**专用库 `ai-gateway`**（独立实例），不与既有业务库共用，无表名冲突风险；
  且为未来 pgvector 向量检索预留了空间。

### 百炼模型服务与可观测性

| 环境变量 | application.yml 占位符 | 说明 |
|----------|------------------------|------|
| `DASHSCOPE_API_KEY` | `${DASHSCOPE_API_KEY:}` | **百炼控制台创建的 API-KEY**，必填（见第三节注入方式） |
| `DASHSCOPE_BASE_URL` | `${DASHSCOPE_BASE_URL:https://token-plan.cn-beijing.maas.aliyuncs.com}` | 上游基址（Token Plan MaaS，仅 scheme+host） |
| `DASHSCOPE_MESSAGES_PATH` | `${DASHSCOPE_MESSAGES_PATH:/apps/anthropic/v1/messages}` | Anthropic Messages 报文端点路径 |
| `DASHSCOPE_COUNT_TOKENS_PATH` | `${DASHSCOPE_COUNT_TOKENS_PATH:/apps/anthropic/v1/messages/count_tokens}` | count_tokens 量算路径 |
| `ANTHROPIC_VERSION` | `${ANTHROPIC_VERSION:2023-06-01}` | 客户端未带 `anthropic-version` 头时的兜底 |
| `DEFAULT_UPSTREAM_MODEL` | `${DEFAULT_UPSTREAM_MODEL:qwen3.7-max}` | 模型名未命中映射时的默认上游模型 |
| `GATEWAY_MASTER_KEY` | `${GATEWAY_MASTER_KEY:}` | 网关引导主密钥（可选），留空则仅认 DB 中的客户端 Key |
| `GATEWAY_CAPTURE_ENABLED` | `${GATEWAY_CAPTURE_ENABLED:true}` | 语料采集开关，关闭则不落库 |
| `SERVER_PORT` | `${SERVER_PORT:8080}` | 网关监听端口 |
| `OTLP_TRACES_ENDPOINT` | `${OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}` | OTLP Trace 上报地址 |
| `OTLP_METRICS_ENDPOINT` | `${OTLP_METRICS_ENDPOINT:http://localhost:4318/v1/metrics}` | OTLP Metric 上报地址 |
| `OTLP_METRICS_EXPORT` | `${OTLP_METRICS_EXPORT:false}` | metric 导出开关；本地无 collector 默认关，compose 内置 collector 置 `true` |
| `OTEL_SAMPLING` | `${OTEL_SAMPLING:0.0}` | trace 采样率；本地默认不采样（保留 Tracer Bean），compose 置 `1.0` |

> ✅ **上游协议已确认 = Anthropic 透传**：网关对接百炼 Token Plan MaaS 的 Anthropic 端点
> （`base-url=https://token-plan.cn-beijing.maas.aliyuncs.com` + path=`/apps/anthropic/v1/messages`），
> 与本文第一节登记的接入点一致。客户端（Claude Code）与上游同讲 Anthropic 协议，网关不做协议翻译，
> 仅注入上游密钥、做 `claude→qwen` 模型名映射、透传 `anthropic-version`/`anthropic-beta` 头并旁路采集语料。
> OpenAI 兼容入口（`/v1/chat/completions`）休眠保留以备回退。详见 `ARCHITECTURE.md`。

### 让 Claude Code 走网关

把 Claude Code 上游指向网关即可（网关替它注入真实百炼密钥，员工拿不到上游密钥）。在 `~/.claude/settings.json`：

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://<网关地址>:8080",
    "ANTHROPIC_AUTH_TOKEN": "<网关下发的 Key 或 GATEWAY_MASTER_KEY>"
  }
}
```

`ANTHROPIC_AUTH_TOKEN` 用**网关 Key**（库里只存 SHA-256），**不是**真实百炼密钥。Claude Code 会把请求发到
`${ANTHROPIC_BASE_URL}/v1/messages`，由网关透传并采集语料。

---

## 三、密钥注入与安全约定

### `DASHSCOPE_API_KEY` 是什么、如何注入

- 它就是**阿里百炼控制台**为账号/业务空间创建的 **API-KEY**（形如 `sk-...`）。
- 网关把它作为 `Authorization: Bearer <key>` 注入到**发往百炼的上游请求**；
  **绝不**写库、**绝不**打日志、**绝不**回传客户端。
- 三种注入方式，任选其一：
  1. **`.env` 文件**（推荐本地/单机）：复制 `.env.example` 为 `.env` 填值，`docker compose up` 时自动加载。
  2. **进程环境变量**：`export DASHSCOPE_API_KEY=<dashscope-api-key>` 后再启动 jar。
  3. **容器/编排平台密钥**：K8s Secret、云效流水线变量等注入到容器环境。
- 客户端访问网关用的是**网关自身下发的 Key**（以 SHA-256 存库，不存明文），
  与上游 `DASHSCOPE_API_KEY` 完全隔离——员工永远拿不到真实的百炼密钥。

### 安全提醒

- 🔐 模型服务密钥、数据库密码、网关主密钥不得以明文登记在 Git 跟踪文件中。
  真实值请放在私有 `.env`、云效变量、KMS 或 K8s Secret，并通过环境变量注入。
- `.env` 已在 `.gitignore` 中，切勿提交真实 `.env`。
