#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 本地/CI 一键：构建 ai-gateway 镜像并推送到阿里云 ACR。
# 之后用 server-deploy.sh 把此 Tag 滚动部署到 ECS（云效 Flow 自动，或手动 SSH 执行）。
#
# 前置：
#   1) 已建好 ACR 命名空间 + 仓库；并 `docker login <ACR_REGISTRY>` 过。
#   2) 本机有 docker buildx（Docker Desktop 自带）。
#   3) ⚠️ SAE 跑 linux/amd64。Apple Silicon(Mac) 直接 build 会产出 arm64、SAE 起不来，
#      所以这里固定 --platform linux/amd64 跨架构构建。（云效 Flow 的 Linux runner 无此问题。）
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."   # 切到仓库根（Dockerfile 所在）

# ===== ACR 目标（已按上海/honyar-iot 实例填好；可用环境变量覆盖）=====
ACR_REGISTRY="${ACR_REGISTRY:-registry.cn-shanghai.aliyuncs.com}"  # 华东2(上海) ACR
ACR_NAMESPACE="${ACR_NAMESPACE:-honyar-iot}"                       # ACR 命名空间
IMAGE_NAME="${IMAGE_NAME:-ai-gateway}"
# 说明：日常发版由云效 Flow 自动构建+推送+部署，本脚本仅作本地手动/应急构建用。
# =====================================================================

# Tag = 时间戳 + git 短 SHA，便于回滚定位
TAG="${1:-$(date +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD 2>/dev/null || echo nogit)}"
IMAGE="${ACR_REGISTRY}/${ACR_NAMESPACE}/${IMAGE_NAME}:${TAG}"

echo ">> 构建并推送（linux/amd64）：${IMAGE}"
docker buildx build --platform linux/amd64 -t "${IMAGE}" --push .

echo ""
echo ">> 完成 ✅  镜像：${IMAGE}"
echo ">> 下一步：用此 Tag 部署到 ECS。"
echo "   - 云效 Flow：已配好的话，git push 即自动构建+部署，无需手动跑本脚本。"
echo "   - 手动部署："
echo "       ssh aliyun-prod-1 'bash /opt/ai-gateway/server-deploy.sh ${IMAGE}'"
echo "       ssh aliyun-prod-2 'bash /opt/ai-gateway/server-deploy.sh ${IMAGE}'"
