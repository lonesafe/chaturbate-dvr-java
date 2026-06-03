#!/bin/bash
echo "[startup] Starting nginx..."
nginx -c /etc/nginx/nginx.conf
NGINX_PID=$!

echo "[startup] Starting Java app..."
java -jar /app/app.jar &
JAVA_PID=$!

echo "[startup] nginx PID=$NGINX_PID, Java PID=$JAVA_PID"

# 任意进程退出时全部终止
trap "echo '[shutdown] Stopping...'; kill -TERM $NGINX_PID $JAVA_PID 2>/dev/null; wait 2>/dev/null; exit 0" SIGTERM SIGINT

wait $NGINX_PID $JAVA_PID