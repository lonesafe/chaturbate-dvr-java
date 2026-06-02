# ============================================================
# Stage 1: Build frontend
# ============================================================
FROM node:22-alpine AS frontend-builder

WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --prefer-offline

COPY frontend/ ./
RUN npm run build

# ============================================================
# Stage 2: Build backend (jar)
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS backend-builder

WORKDIR /build
COPY pom.xml ./
RUN mvn dependency:go-offline -B -q

COPY src ./src
COPY src/main/resources/static/ /build/src/main/resources/static/
RUN mvn package -DskipTests -q

# ============================================================
# Stage 3: Runtime (nginx + Java)
# ============================================================
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache nginx bash curl ffmpeg

WORKDIR /app

# --- nginx 配置 ---
COPY nginx.conf /etc/nginx/nginx.conf

# --- 复制后端 jar ---
COPY --from=backend-builder /build/target/chaturbate-dvr-1.0.0.jar /app/app.jar

# --- 复制前端构建产物 ---
COPY --from=frontend-builder /build/frontend/dist /usr/share/nginx/html/app

# --- 创建必要目录 ---
RUN mkdir -p /app/recordings /app/data /app/tmp

# --- 启动脚本 ---
RUN cat > /app/start.sh <<'SCRIPT'
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
SCRIPT

RUN chmod +x /app/start.sh

EXPOSE 80

ENTRYPOINT ["/app/start.sh"]
