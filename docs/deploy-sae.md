# AI 网关 · 生产无感发布（阿里云 SAE + 云效 Flow 全自动）

目标：`git push` → 自动构建镜像 → 部署 SAE（分批/灰度 + 健康检查 + 优雅停机 + 失败自动回滚），用户无感。

**实际环境（全部华东2·上海 cn-shanghai，同一 VPC）**
- 代码：云效 Codeup `smart-home-v3-group/ai-gateway`（分支 `main`）
- 镜像：ACR `registry.cn-shanghai.aliyuncs.com/honyar-iot/ai-gateway`
- 运行：SAE（与 RDS 同一 VPC）
- 上游：百炼 `token-plan.cn-beijing.maas.aliyuncs.com`（公网访问，需 VPC 出网）

---

## 0. 应用侧"无感地基"（已在代码里，无需再改）
- `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=${SHUTDOWN_TIMEOUT:90s}`：放完在途 SSE 流再退出。
- `management.endpoint.health.probes.enabled=true`：`/actuator/health/liveness`、`/readiness`（默认不含 db，避开 RDS 掐空闲连接导致的误判）。
- 多阶段 `Dockerfile`（自带 Maven 构建）、`.dockerignore` 已排除 `.env`。

> 构建归属：**由云效 Flow 构建**。因此 ACR 仓库里的「代码变更自动构建镜像」请**关闭**，避免与 Flow 重复构建。

---

## 1. 一次性准备

### 1.1 首个镜像（bootstrap，给 SAE 建应用时选）
SAE 建应用要选一个镜像，但 Flow 还没跑过。用现成的 ACR 自动构建打第一个版本即可（暂时借用，之后关掉）：
```bash
git tag release-v0.0.1
git push origin release-v0.0.1     # 触发 ACR 构建 honyar-iot/ai-gateway:0.0.1
```
在 ACR 仓库「构建」页看到这条构建变绿即可。

### 1.2 建 SAE 应用
SAE 控制台 → 应用管理 → 创建应用：
| 项 | 值 |
|---|---|
| 地域 | 华东2（上海） |
| **VPC** | **与 RDS 同一个 VPC + 可达 RDS 的交换机/安全组** |
| 实例数 | **2** |
| 规格 | 1 vCPU 2 GiB 起 |
| 部署方式 | 镜像 → `registry.cn-shanghai.aliyuncs.com/honyar-iot/ai-gateway:0.0.1` |
| 端口 | 8080 |
| 环境变量 | `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`、`DASHSCOPE_API_KEY`、`GATEWAY_MASTER_KEY`、`DIGITAL_WORKER_ENABLED=true`（密钥用 SAE 配置加密） |
| **存活检查 liveness** | HTTP `GET /actuator/health/liveness` : 8080 |
| **就绪检查 readiness** | HTTP `GET /actuator/health/readiness` : 8080 |
| **无损上线下线** | 开启；下线/停止前等待 **≥ 90s**（对齐 `SHUTDOWN_TIMEOUT`，放完 SSE 流） |
| 接入层 SLB | 绑定后空闲超时调大（≥5 分钟），避免工具检索静默被掐断 |

> ⚠️ 健康检查别用 `/actuator/health`（含 db，RDS 掐空闲连接会偶发 DOWN → 误踢节点）。

### 1.3 打通网络（漏了应用起不来）
1. **RDS 白名单**：把 **SAE 所在 VPC 网段**（或 SAE 安全组）加入 RDS 白名单（原来只白名单了本机 IP）。
2. **VPC 出网到百炼**：给该 VPC 配 **NAT 网关 + EIP**，保证能访问 `token-plan.cn-beijing.maas.aliyuncs.com`。

### 1.4 验证
SAE 实例健康（liveness/readiness 绿）；`curl http://<SAE接入地址>/actuator/health/liveness` → 200。

---

## 2. 云效 Flow 全自动流水线
云效 → 流水线 → 新建（Java·镜像·部署模板或空模板），三段（任务名以界面为准）：

1. **流水线源**：Codeup `ai-gateway`、分支 `main`，勾选 **代码提交自动触发**。
2. **构建 · 镜像构建并推送 ACR**：Dockerfile=`./Dockerfile`，目标仓库=`honyar-iot/ai-gateway`，Tag 用 `${CI_COMMIT_SHORT_SHA}` 或时间戳。首次按引导**授权云效访问 ACR**。
3. **部署 · Serverless 应用引擎 SAE**：选上面建的 SAE 应用，镜像=上一步产物；策略选 **分批（2 批）+ 失败自动回滚**，可加灰度。首次按引导**授权云效访问 SAE（RAM 角色）**。

保存并手动跑一次确认通。

## 3. 关闭 ACR 自动构建（避免双构建）
ACR → 镜像仓库 `ai-gateway` → 构建 → 把「代码变更自动构建镜像」**关掉**（仓库保留，Flow 会往里推镜像）。删掉 `release-v0.0.1` 的构建规则也可以。从此**构建只由 Flow 负责**。

## 4. 日常发版
```bash
git push            # 到 main → Flow 自动：构建→推 ACR→分批灰度部署 SAE→失败自动回滚
```
全程用户无感。发完验证 `/actuator/health/liveness` 与问 pm-agent「查看每个员工本月的 AI 使用情况」。

> 想更可控（避免每个提交都上生产）：把 Flow 触发器从「main 推送」改成「打 tag 触发」，或在部署前加一道人工卡点。

## 5. 数据库变更纪律（频繁发版必读）
- 滚动期新旧版本同时在线 → schema 变更只能**加法**（`ADD COLUMN/TABLE IF NOT EXISTS`、`CREATE OR REPLACE VIEW`）；**禁止**删列/改名/改类型。
- 现由启动期 `R2dbcInitConfig` 跑 `schema.sql`（幂等）。频繁发版建议迁 **Flyway**（迁移锁，防两实例同时建表打架）。

## 6. 公网接入：借道现有 CLB + 2 台 ECS Nginx → SAE 内网 SLB

**架构（2026-06-03 落地确认）**：不复购 ALB，通过现有老 CLB + ECS Nginx 回源 SAE 内网 SLB。

```
用户 → WAF(all.hongyanyun.cn.c.yundunwaf.com) → 老CLB(443 HTTPS) → 2台ECS Nginx(80 HTTP)
     → SAE内网SLB(172.19.161.87:80) → SAE容器(:8080)
```

| 组件 | 值 | 说明 |
|---|---|---|
| 域名 | `ai-gateway.hongyanyun.cn` | DNS CNAME → `all.hongyanyun.cn.c.yundunwaf.com`(WAF 接入点) |
| WAF | 已配该域名，通配符证书 `*.hongyanyun.cn` | 安全清洗 |
| 老 CLB 公网 IP | `47.101.31.232` | 443 监听(HTTPS, 已挂通配符证书) → 虚拟服务器组 80 端口 |
| 老 CLB 证书 | `*.hongyanyun.cn`(杭州鸿雁物联网) | Xcc Trust OV SSL CA |
| ECS 生产 1 | `106.15.225.123`(公网) / `172.19.161.75`(内网) | SSH: `ssh aliyun-prod-1` |
| ECS 生产 2 | `106.14.176.72`(公网) / `172.19.161.74`(内网) | SSH: `ssh aliyun-prod-2` |
| **Nginx 路径** | `/root/nginx/sbin/nginx`(PID 76648/1382075) | 配置文件 `/root/nginx/conf/nginx.conf`(末尾 `include conf.d/*.conf`) |
| **Nginx 新增配置** | `/root/nginx/conf/conf.d/ai-gateway.conf` | ⚠️ 仅新增此独立文件，不碰 `nginx.conf` 中其它 server 块 |
| **SAE 内网 SLB** | `172.19.161.87:80`(固定内网 IP) | SAE 应用「基于 CLB 访问」→ 内网 → 新建 |
| SAE 健康检查 | `/actuator/health/readiness`(HTTP, 200) | SAE 侧已配就绪+存活探针；CLB 侧默认探 `/`，需改为该路径(待用户在 SAE 控制台操作) |

**Nginx 配置要点（已落地）**：
- `upstream sae_ai_gateway { server 172.19.161.87:80; keepalive 32; }`
- `server_name ai-gateway.hongyanyun.cn` + `proxy_pass http://sae_ai_gateway`
- SSE 流式三件套：`proxy_http_version 1.1` / `Connection ""` / `proxy_buffering off`
- 超大任务超时：`proxy_read_timeout 1800s`(30 分钟，覆盖 http 块默认 90s)
- `X-Forwarded-Proto https`(CLB 已卸 HTTPS，固定告诉后端原始是 https)
- `client_max_body_size 20m`(≥ 应用 `max-in-memory-size: 16MB`)

> ⚠️ **CLB 健康检查待确认**：SAE 创建的默认内网 SLB 健康检查路径在控制台不可修改。当前 Nginx 对 `/`(CLB 默认探) 返回 401(鉴权拦截)。若 CLB 健康检查以 4xx 判为不健康，需后续改为探 `/actuator/health/readiness`。
>
> ⚠️ **现有服务安全**：所有配置在独立 `conf.d/ai-gateway.conf`，`nginx -t` 通过后 reload，失败自动回滚，不碰其它 server 块（工作台/康养/api等）。

## 附：本地手动/应急构建
`scripts/build-push.sh`（默认已填 上海/honyar-iot）：`./scripts/build-push.sh` → 本地 `docker buildx --platform linux/amd64` 构建并推 ACR。日常用 Flow，不需要它。
