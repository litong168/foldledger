# 滴水记账双端同步服务端镜像（NAS 离线部署用）
# 构建：docker build --platform linux/amd64 -t foldledger/sync-server:1.0.0 .
# 运行：全部配置走环境变量（NAS Web UI 无命令行能力），与 server.js 的 SYNC_* 对应：
#   SYNC_HOST / SYNC_PORT / SYNC_DATA / SYNC_TOKEN
# 示例：SYNC_HOST=0.0.0.0  SYNC_PORT=8787  SYNC_DATA=/data/ledger.json  SYNC_TOKEN=你的密钥
# 数据挂载：宿主「个人空间/gitea/ledger-sync/data」→ 容器 /data
FROM node:20.19-alpine3.20
WORKDIR /app
COPY server.js /app/server.js
ENV SYNC_HOST=0.0.0.0
ENV SYNC_PORT=8787
EXPOSE 8787
VOLUME ["/data"]
CMD ["node", "server.js"]
