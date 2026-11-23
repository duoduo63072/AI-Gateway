#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 应急手动部署（日常由云效流水线自动执行，不需要手动跑此脚本）
# 前提：/web/compose/docker-compose.prod.yml 已存在（流水线首次运行后自动创建）
# ---------------------------------------------------------------------------
set -euo pipefail

COMPOSE_FILE="/opt/ai-gateway/docker-compose.prod.yml"

if [ ! -f "/opt/ai-gateway/.env" ]; then
  echo "Missing /opt/ai-gateway/.env. Restore it from private secret storage before deploying." >&2
  exit 1
fi

echo ">> 拉取最新镜像..."
docker-compose -f "${COMPOSE_FILE}" pull ai-gateway

echo ">> 重启容器..."
docker-compose -f "${COMPOSE_FILE}" up -d --no-deps ai-gateway

echo ">> 清理虚悬镜像..."
docker image prune -f

echo ">> 完成 ✅"
