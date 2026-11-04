#!/usr/bin/env bash
# 起可观测性栈：Prometheus 抓 Windows 侧的 app:8080，Grafana 出看板。
#
#   bash docker/up-monitoring.sh          # 只起 prometheus + grafana
#   bash docker/up-monitoring.sh all      # 连 redis/rabbitmq/app 一起起
#
# WINDOWS_HOST_IP：容器里 host.docker.internal 要解析成的地址。Docker Desktop 的引擎能认
# host-gateway；纯 WSL2 里跑的 docker engine 不认（实测解析成网桥 172.17.0.1，连不上宿主的 8080），
# 那边「默认路由的下一跳」才是 Windows，所以没给值时按默认路由取一次。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${WINDOWS_HOST_IP:-}" ]; then
  gw=$(ip route 2>/dev/null | awk '/default/ {print $3; exit}' || true)
  if [ -n "${gw:-}" ]; then
    export WINDOWS_HOST_IP="$gw"
    echo "[up-monitoring] WINDOWS_HOST_IP 自动取默认路由 = $gw"
  else
    export WINDOWS_HOST_IP="host-gateway"
    echo "[up-monitoring] 取不到默认路由，退回 host-gateway（Docker Desktop 引擎上这才是对的）"
  fi
fi

if [ "${1:-}" = "all" ]; then
  docker compose up -d
else
  docker compose up -d prometheus grafana
fi

echo
echo "Prometheus  http://localhost:9090/targets   （job mkbai-app 应当是 up）"
echo "Grafana     http://localhost:3000  admin/admin  → 看板 rag-custom"
