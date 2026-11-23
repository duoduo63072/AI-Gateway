# 故障排查 / 已知问题

记录生产环境踩过的坑、根因与处置方法。新增条目请保留「现象 → 判定 → 根因 → 处置 → 复验」结构。

---

## 1. Claude Code 用过工具后报 `API Error: 405`

**现象**
- 简单问答正常；一旦助手跑了 shell 工具（`bash`/`curl`/`rm`/`chmod` 等），后续请求全部 `API Error: 405`。
- 之后连新开的「你好」也 405——因为多轮对话每轮都会把含工具命令的历史整包重发。

**判定（区分是哪一层）**
同域名、同路径 `POST /v1/messages`、同方法，只改 body 内容对比状态码：

```bash
# 干净 body —— 期望 401（穿过 WAF 到网关，被假 token 拒）
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://ai-gateway.hongyanyun.cn/v1/messages \
  -H "Authorization: Bearer test-invalid" -H "Content-Type: application/json" \
  -d '{"model":"x","max_tokens":10,"messages":[{"role":"user","content":"hello"}]}'

# 含 shell 命令 body —— 若返回 405 即被 WAF 拦截
curl -s -i -X POST https://ai-gateway.hongyanyun.cn/v1/messages \
  -H "Authorization: Bearer test-invalid" -H "Content-Type: application/json" \
  -d '{"model":"x","max_tokens":10,"messages":[{"role":"user","content":"chmod 777 /tmp; rm -rf /"}]}'
```

干净→401、含命令→405，且 405 响应是 `Server: Tengine` 的阿里云拦截页（含 `traceid`）→ 即 **WAF 拦截**，与网关、与百炼无关。网关代码若是上游返回的错误会落 `upstream_error` 日志，日志里没有即可佐证。

**根因**
链路里的阿里云 WAF（`all.hongyanyun.cn.c.yundunwaf.com`，位于 CLB/Nginx 之前）开了「基础 Web 攻击防护 / 命令执行(RCE)」规则，会扫描请求体。Claude Code 工具调用把 shell 命令文本写进了请求体，被 RCE 正则判成攻击，返回 405。

**处置（WAF 控制台，非代码）**
对域名 `ai-gateway.hongyanyun.cn` 加白名单/自定义防护策略：路径前缀 `/v1/` **放行并跳过「规则防护引擎」这一个模块**，其余模块（CC 限流、IP 信誉、Bot 防护）保留。访问控制本就在网关 `ApiKeyAuthFilter`（Bearer/x-api-key 强鉴权），跳过该域名的正则防护可接受。
> 更精准可按拦截页 `traceid` 在 WAF 日志里定位命中的具体规则 ID 单独关闭。

**复验**：改完用含 shell 命令的 body + 假 token 打公网域名，**405 应变 401**。

---

## 2. actuator 端点公网无鉴权泄露运维信息

**现象**
公网无需鉴权即可拉 `/actuator/metrics`（指标名/数值：模型名、token 用量、QPS 等）与 `/actuator/info`（构建信息），返回 200。

**根因**
1. `application.yml` 的 `management.endpoints.web.exposure.include` 曾为 `health,info,metrics,prometheus`；
2. `ApiKeyAuthFilter.isPublic()` 曾放行整个 `/actuator` 前缀（匿名）。
两者叠加 + actuator 经 nginx 直达公网 → 无鉴权信息泄露。

**处置（已修复，提交 `12b1a82`）**
- 暴露面收窄为仅 `health`（指标走 OTLP 推送，HTTP pull 端点无人用）。
- `isPublic()` 收窄为只放行 `/actuator/health*`，其余 actuator 端点须 Key（纵深防御：即便日后误把端点加回 exposure 也有兜底）。
- SAE/CLB 健康探针 `/actuator/health/liveness|readiness` 不受影响。

**复验**
```bash
curl -s -o /dev/null -w "%{http_code}\n" https://ai-gateway.hongyanyun.cn/actuator/health/liveness  # 期望 200
curl -s -o /dev/null -w "%{http_code}\n" https://ai-gateway.hongyanyun.cn/actuator/metrics          # 期望 401
curl -s -o /dev/null -w "%{http_code}\n" https://ai-gateway.hongyanyun.cn/actuator/info             # 期望 401
```
