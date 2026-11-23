# AI 网关 · ECS 生产部署（云效 Flow 全自动滚动发布）

目标：`git push` → 自动构建镜像推 ACR → VMDockerDeploy 依次滚动更新 2 台 ECS → 全程无感。

**实际环境**
- 代码：云效 Codeup `smart-home-v3-group/ai-gateway`（分支 `main`）
- 镜像：ACR `registry.cn-shanghai.aliyuncs.com/honyar-iot/ai-gateway`
- 运行：2 台 ECS，Docker 容器，端口 8080
- 上游：百炼 `token-plan.cn-beijing.maas.aliyuncs.com`

```
用户 → WAF → 老CLB(443 HTTPS) → 2台ECS Nginx(80) → localhost:8080(Docker容器)
```

| 服务器 | 公网 IP | 内网 IP | SSH 别名 |
|---|---|---|---|
| 生产 1 | `106.15.225.123` | `172.19.161.75` | `ssh aliyun-prod-1` |
| 生产 2 | `106.14.176.72` | `172.19.161.74` | `ssh aliyun-prod-2` |

---

## 0. 应用侧"无感地基"（已在代码里，无需再改）

- 生产密钥不进镜像；服务器需准备 `/opt/ai-gateway/.env`（从私有密钥备份恢复）。
- `server.shutdown=graceful` + `SHUTDOWN_TIMEOUT=90s`：放完在途 SSE 流再退出。
- `docker-compose.prod.yml` 的 `stop_grace_period: 95s` 保证 Docker 等够时间。
- `/actuator/health/readiness`：就绪探针（docker-compose healthcheck + Nginx 上游健康检查）。

---

## 1. 一次性准备

### 1.1 服务器安装 docker-compose

```bash
# 两台服务器各执行
ssh aliyun-prod-1 "curl -fsSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose && chmod +x /usr/local/bin/docker-compose"
ssh aliyun-prod-2 "curl -fsSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose && chmod +x /usr/local/bin/docker-compose"

# 验证
ssh aliyun-prod-1 "docker-compose --version"
ssh aliyun-prod-2 "docker-compose --version"
```

### 1.2 服务器登录 ACR（仅首次）

```bash
ssh aliyun-prod-1 "docker login registry.cn-shanghai.aliyuncs.com"
ssh aliyun-prod-2 "docker login registry.cn-shanghai.aliyuncs.com"
```

### 1.3 更新 Nginx upstream（每台服务器）

原配置 (`/root/nginx/conf/conf.d/ai-gateway.conf`) 的 upstream 指向 SAE 内网 SLB，改为本机：

```nginx
upstream ai_gateway {
    server 127.0.0.1:8080;
    keepalive 32;
}
```

操作：

```bash
# 在每台服务器上执行
sed -i 's/server 172\.19\.161\.87:80/server 127.0.0.1:8080/' \
    /root/nginx/conf/conf.d/ai-gateway.conf

/root/nginx/sbin/nginx -t && /root/nginx/sbin/nginx -s reload
```

### 1.4 云效主机组

云效 → 部署 → 主机组 → 新建主机组，将两台 ECS 加入（按引导在服务器上安装云效 Agent）。
记下主机组 ID，填入 `flow.yml` 中的 `machineGroup` 字段。

### 1.5 在云效导入流水线

云效 → 流水线 → 新建 → 选「YAML 模式」，上传 / 导入 `flow.yml`，手动跑一次验证通过。

> 首次运行会自动创建 `/opt/ai-gateway/docker-compose.prod.yml` 并启动容器，验证：
> ```bash
> curl https://ai-gateway.hongyanyun.cn/actuator/health/readiness
> ```

---

## 2. 流水线说明（`flow.yml`）

两个阶段：

| 阶段 | 内容 |
|---|---|
| 构建 | `ACRDockerBuild`：运行 Dockerfile（内含 Maven 构建），推 `honyar-iot/ai-gateway:${DATETIME}` + `latest` |
| 部署 | `VMDockerDeploy`：分 2 批，第 1 批暂停确认后再做第 2 批；每台执行 `docker-compose pull + up` |

**关键配置说明：**
- `extraTags: [latest]`：每次构建同时打 `latest` tag，compose 文件用 `:latest`，pull 自动拿最新。
- `stop_grace_period: 95s`：Docker 给容器 95 秒优雅退出时间（≥ `SHUTDOWN_TIMEOUT=90s`）。
- `pauseStrategy: firstBatchPause`：第 1 台部署完暂停，人工验证后手点继续第 2 台。

---

## 3. 日常发版

```bash
git push   # → Flow 自动：构建→推 ACR→部署生产1(暂停确认)→部署生产2
```

---

## 4. 应急回滚

```bash
# 在目标服务器上，改 compose 文件的 image tag 为上一个版本，再 up
ssh aliyun-prod-1
  vi /opt/ai-gateway/docker-compose.prod.yml   # 把 :latest 改成 :<上一个DATETIME>
  docker-compose -f /opt/ai-gateway/docker-compose.prod.yml up -d --no-deps ai-gateway
```

或者直接在 ACR 控制台把 `latest` tag 指向上一个镜像，然后在服务器上：
```bash
docker-compose -f /opt/ai-gateway/docker-compose.prod.yml pull ai-gateway
docker-compose -f /opt/ai-gateway/docker-compose.prod.yml up -d --no-deps ai-gateway
```

---

## 5. 数据库变更纪律

- 滚动期新旧版本同时在线 → schema 变更只能**加法**（`ADD COLUMN/TABLE IF NOT EXISTS`）；禁止删列/改名/改类型。
- 现由 `R2dbcInitConfig` 启动时跑 `schema.sql`（幂等）。

---

## 6. 常用运维命令

```bash
# 查看容器状态
ssh aliyun-prod-1 "docker ps | grep ai-gateway"

# 查看实时日志
ssh aliyun-prod-1 "docker logs -f --tail=100 ai-gateway"

# 查看容器资源占用
ssh aliyun-prod-1 "docker stats ai-gateway --no-stream"

# 手动健康检查
ssh aliyun-prod-1 "curl -s http://localhost:8080/actuator/health/readiness"

# 应急手动重新部署（需流水线至少跑过一次）
ssh aliyun-prod-1 "bash /opt/ai-gateway/server-deploy.sh"
```

---

## 附：本地手动/应急构建

```bash
./scripts/build-push.sh
```

本地 `docker buildx --platform linux/amd64` 构建并推 ACR（Mac arm64 必须跨架构）。日常用 Flow，不需要手动跑。
