# AI 网关 · 项目速查

企业内部 AI 网关：将员工 Claude Code（Anthropic 协议）透传到阿里百炼 Token Plan MaaS，旁路异步采集对话语料，用于训练数字员工。

- 技术栈：Java 17 · Spring Boot 3.3 WebFlux · R2DBC · PostgreSQL（阿里云 RDS）
- 详细架构：`docs/architecture/ARCHITECTURE.md`

---

## 生产环境

### 服务器

| | 公网 IP | 内网 IP | SSH 别名 |
|---|---|---|---|
| 生产 1 | `106.15.225.123` | `172.19.161.75` | `ssh aliyun-prod-1` |
| 生产 2 | `106.14.176.72` | `172.19.161.74` | `ssh aliyun-prod-2` |

### 流量链路

```
用户
 → WAF (all.hongyanyun.cn.c.yundunwaf.com)
 → 老 CLB 公网 IP 47.101.31.232 (443 HTTPS，通配符证书 *.hongyanyun.cn)
 → 2 台 ECS Nginx (:80)  [/root/nginx/conf/conf.d/ai-gateway.conf]
 → localhost:8091 (Docker 容器 ai-gateway，8091→8080)
 → 阿里百炼 token-plan.cn-beijing.maas.aliyuncs.com (公网出)
```

- 域名：`ai-gateway.hongyanyun.cn`（DNS CNAME → WAF 接入点）
- Nginx 配置路径：`/root/nginx/conf/conf.d/ai-gateway.conf`（upstream → 127.0.0.1:8091，独立文件，不改 nginx.conf）
- Nginx 可执行文件：`/root/nginx/sbin/nginx`

### 数据库

- 阿里云 RDS PostgreSQL，华东 2（上海），与 ECS 同 VPC
- 内网地址：`pgm-uf6k6r076121nyq2.pg.rds.aliyuncs.com:5432`
- 库名：`ai-gateway`，用户：`honyar`

### 镜像仓库

- ACR（华东 2·上海）：`registry.cn-shanghai.aliyuncs.com/honyar-iot/ai-gateway`
- 每次构建同时打 `${DATETIME}` + `latest` 两个 tag

---

## 部署方式

### 日常发版（全自动）

```bash
git push   # main 分支推送 → 云效 Flow 自动触发
```

流水线（`flow.yml`）两阶段：
1. **构建**：`ACRDockerBuild` 在公共 runner 上执行 Dockerfile（内含 Maven 构建），推镜像到 ACR
2. **部署**：`VMDockerDeploy` 分 2 批滚动更新，第 1 台部署后暂停，人工确认再继续第 2 台

云效主机组 ID：`t2EsxSdE4UcuJEyn`（已含两台 ECS）

每台服务器上执行的部署命令（流水线自动）：
```bash
mkdir -p /web/compose
cp /root/workspace/repo_0/docker-compose.prod.yml /web/compose/docker-compose.prod.yml
docker-compose -f /web/compose/docker-compose.prod.yml pull ai-gateway
docker-compose -f /web/compose/docker-compose.prod.yml up -d --no-deps ai-gateway
docker image prune -f
```

### 应急手动部署

```bash
# 先在本机构建推镜像（Mac arm64 必须跨架构）
./scripts/build-push.sh

# 再在两台服务器上分别执行
ssh aliyun-prod-1 "bash /opt/ai-gateway/server-deploy.sh"
ssh aliyun-prod-2 "bash /opt/ai-gateway/server-deploy.sh"
```

### 应急回滚

```bash
# 登录服务器，把 compose 文件里的 :latest 改成上一个 DATETIME tag
ssh aliyun-prod-1
vi /web/compose/docker-compose.prod.yml
docker-compose -f /web/compose/docker-compose.prod.yml up -d --no-deps ai-gateway
```

---

## 环境变量

生产配置不再打包进镜像；容器运行时从服务器 `/opt/ai-gateway/.env` 或编排平台 Secret 注入。

| 变量 | 说明 |
|---|---|
| `DB_HOST` | RDS 内网地址 |
| `DASHSCOPE_API_KEY` | 百炼 API Key |
| `DASHSCOPE_BASE_URL` | 百炼 Token Plan MaaS 地址 |
| `GATEWAY_MASTER_KEY` | 网关主密钥（员工 Bearer Token） |
| `DIGITAL_WORKER_ENABLED` | 数字员工功能开关 |

本地开发：项目根 `.env`（已 gitignore），`set -a; . ./.env; set +a; java -jar target/ai-gateway-*.jar`

---

## 常用运维命令

```bash
# 查看容器状态
ssh aliyun-prod-1 "docker ps | grep ai-gateway"

# 实时日志
ssh aliyun-prod-1 "docker logs -f --tail=100 ai-gateway"

# 健康检查
ssh aliyun-prod-1 "curl -s http://localhost:8080/actuator/health/readiness"
curl https://ai-gateway.hongyanyun.cn/actuator/health/readiness

# 资源占用
ssh aliyun-prod-1 "docker stats ai-gateway --no-stream"

# Nginx 重载（改配置后）
ssh aliyun-prod-1 "/root/nginx/sbin/nginx -t && /root/nginx/sbin/nginx -s reload"
```

---

## 网关接口

所有 `/v1/*` 接口均走全局 `ApiKeyAuthFilter` 鉴权（`x-api-key` 或 `Authorization: Bearer`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/messages` | Anthropic Messages 主入口：Claude Code 经此透传到百炼，旁路异步采集语料 |
| `POST` | `/v1/messages/count_tokens` | 上下文量算，纯透传不采集 |
| `GET` | `/v1/models` | 模型列表（静态清单）：供 CC Switch「获取模型列表」一键拉取并自动映射 |
| `POST` | `/v1/chat/completions` | OpenAI 兼容入口（已休眠，保留以备回退） |

新增/下线模型时改 `ModelsHandler.java` 里的 `MODELS_JSON` 常量即可。

---

## 关键文件

| 文件 | 说明 |
|---|---|
| `flow.yml` | 云效流水线配置 |
| `Dockerfile` | 多阶段构建（Maven build + JRE runtime），`ARG APP_ENV=prod` |
| `docker-compose.prod.yml` | 生产部署用 compose |
| `docker-compose.yml` | 本地开发用（含 OTEL Collector） |
| `.env.prod` | 本地私有生产环境变量备份（gitignore，不入库；生产服务器使用 `/opt/ai-gateway/.env`） |
| `.env` | 本地开发环境变量（gitignore，不入库） |
| `scripts/entrypoint.sh` | 容器启动脚本（source .env → java） |
| `scripts/build-push.sh` | 本地手动构建推 ACR |
| `scripts/server-deploy.sh` | 应急手动部署脚本（服务器上执行） |
| `docs/deploy-ecs.md` | 完整 ECS 部署手册（含一次性准备步骤） |
| `docs/runbooks/troubleshooting.md` | 故障排查/已知问题（WAF 误杀工具调用 405、actuator 暴露等） |
| `src/main/resources/schema.sql` | DB schema（启动时幂等执行） |
| `src/.../web/ModelsHandler.java` | `/v1/models` 模型清单 handler（静态 JSON，改这里增删模型） |

---

## 数据库变更纪律

滚动发布期间新旧版本同时运行，schema 变更**只能加法**（ADD COLUMN/TABLE IF NOT EXISTS）；禁止删列、改名、改类型。
