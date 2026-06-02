# Chaturbate DVR API 接口文档

## 基础信息

- **基础路径**: `http://localhost:8080/api`
- **内容类型**: `application/json`
- **跨域支持**: 已开启（允许所有来源）
- **数据库**: H2 (`jdbc:h2:./data/dvr`)

---

## 1. 直播间管理接口

**基础路径**: `/api/channels`

### 1.1 获取所有直播间

- **接口**: `GET /api/channels`
- **描述**: 获取所有直播间列表
- **响应示例**:
```json
[
  {
    "id": 1,
    "username": "cristinavegas",
    "displayName": "Cristina Vegas",
    "enabled": true,
    "createdAt": "2026-06-02T10:00:00",
    "updatedAt": "2026-06-02T10:00:00"
  }
]
```

### 1.2 获取启用的直播间

- **接口**: `GET /api/channels/enabled`
- **描述**: 获取所有启用监控的直播间
- **响应**: 同 1.1

### 1.3 获取正在录制的直播间

- **接口**: `GET /api/channels/recording`
- **描述**: 获取所有正在录制的直播间（从内存获取）
- **响应**: 同 1.1

### 1.4 获取所有频道的当前状态

- **接口**: `GET /api/channels/statuses`
- **描述**: 获取所有频道的当前状态（public/private/offline）
- **响应示例**:
```json
{
  "cristinavegas": "public",
  "somechannel": "offline"
}
```

### 1.5 添加直播间

- **接口**: `POST /api/channels`
- **描述**: 添加新的直播间到监控列表
- **请求体**:
```json
{
  "username": "cristinavegas",
  "displayName": "Cristina Vegas"
}
```
- **响应示例**:
```json
{
  "success": true,
  "message": "添加成功",
  "data": {
    "id": 1,
    "username": "cristinavegas",
    "displayName": "Cristina Vegas",
    "enabled": true
  }
}
```
- **错误响应** (400):
  - `"用户名不能为空"`
  - `"直播间已存在"`

### 1.6 更新直播间

- **接口**: `PUT /api/channels/{id}`
- **描述**: 更新直播间信息
- **路径参数**:
  - `id` (Long): 直播间 ID
- **请求体**: `Channel` 对象（JSON）
- **响应示例**:
```json
{
  "success": true,
  "message": "更新成功"
}
```

### 1.7 删除直播间

- **接口**: `DELETE /api/channels/{id}`
- **描述**: 删除直播间（如果正在录制，先停止）
- **路径参数**:
  - `id` (Long): 直播间 ID
- **响应示例**:
```json
{
  "success": true,
  "message": "删除成功"
}
```

### 1.8 启用/禁用直播间

- **接口**: `PUT /api/channels/{id}/toggle`
- **描述**: 切换直播间的启用状态
- **路径参数**:
  - `id` (Long): 直播间 ID
- **响应示例**:
```json
{
  "success": true,
  "enabled": true
}
```

### 1.9 开始录制

- **接口**: `POST /api/channels/{username}/start`
- **描述**: 手动开始录制指定直播间
- **路径参数**:
  - `username` (String): 主播用户名
- **响应示例**:
```json
{
  "success": true,
  "message": "开始录制成功",
  "taskId": "cristinavegas-20260602-100000"
}
```
- **错误响应** (400):
  - `"无法获取直播间信息"`
  - `"直播间当前没有HLS源"`
  - `"该直播间已在录制中"`

### 1.10 停止录制

- **接口**: `POST /api/channels/{username}/stop`
- **描述**: 停止录制（会立即合并未合并的分片，然后清理临时文件）
- **路径参数**:
  - `username` (String): 主播用户名
- **响应示例**:
```json
{
  "success": true,
  "message": "停止录制成功"
}
```
- **错误响应** (400):
  - `"该直播间未在录制"`

### 1.11 获取下载信息

- **接口**: `GET /api/channels/{username}/download-info`
- **描述**: 获取指定直播间的当前下载信息（活跃下载线程、运行时长等）
- **路径参数**:
  - `username` (String): 主播用户名
- **响应示例**:
```json
{
  "active": true,
  "username": "cristinavegas",
  "downloadUrl": "https://...",
  "runtimeSeconds": 120,
  "partCount": 5,
  "format": "fMP4",
  "isStopped": false,
  "activeDownloads": [
    {
      "url": "https://.../segment1.m4s",
      "type": "video",
      "filename": "segment1.m4s",
      "elapsedSeconds": 2,
      "startTime": "10:05:30"
    }
  ],
  "activeDownloadCount": 1
}
```

### 1.12 获取录制日志

- **接口**: `GET /api/channels/{username}/logs`
- **描述**: 获取指定直播间的录制日志
- **路径参数**:
  - `username` (String): 主播用户名
- **响应示例**:
```json
{
  "success": true,
  "isRunning": true,
  "logs": [
    "2026-06-02 10:05:30 [pool-2-thread-1] INFO  - 开始录制任务: cristinavegas",
    "2026-06-02 10:05:31 [pool-2-thread-1] INFO  - 解析 master.m3u8 完成"
  ]
}
```

---

## 2. 系统配置接口

**基础路径**: `/api/config`

### 2.1 获取所有配置

- **接口**: `GET /api/config`
- **描述**: 获取所有系统配置
- **响应示例**:
```json
[
  {
    "id": 1,
    "configKey": "cookie",
    "configValue": "",
    "description": "Cloudflare cf_clearance cookie"
  }
]
```

### 2.2 获取指定配置

- **接口**: `GET /api/config/{key}`
- **描述**: 获取指定配置项的值
- **路径参数**:
  - `key` (String): 配置键
- **响应示例**:
```json
{
  "configKey": "record_path",
  "configValue": "./recordings/{username}/{username}-{yyyy-mm-dd}.mp4",
  "description": "录制文件保存路径"
}
```

### 2.3 更新配置

- **接口**: `PUT /api/config/{key}`
- **描述**: 更新指定配置项
- **路径参数**:
  - `key` (String): 配置键
- **请求体**:
```json
{
  "configValue": "新的配置值"
}
```
- **响应示例**:
```json
{
  "success": true,
  "message": "配置更新成功"
}
```

### 2.4 批量更新配置

- **接口**: `PUT /api/config/batch`
- **描述**: 批量更新多个配置项
- **请求体**:
```json
{
  "cookie": "cf_clearance=xxx",
  "user_agent": "Mozilla/5.0...",
  "record_path": "./recordings"
}
```
- **响应示例**:
```json
{
  "success": true,
  "message": "批量更新成功"
}
```

### 2.5 删除配置

- **接口**: `DELETE /api/config/{key}`
- **描述**: 删除指定配置项（恢复默认值）
- **路径参数**:
  - `key` (String): 配置键
- **响应示例**:
```json
{
  "success": true,
  "message": "删除成功"
}
```

---

## 3. 数据模型

### 3.1 Channel（直播间）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 ID |
| username | String | 主播用户名（唯一标识） |
| displayName | String | 显示名称 |
| enabled | Boolean | 是否启用监控 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

> **注意**: `recording`、`lastStatus` 等状态字段不再存储在数据库中，而是通过内存管理。

### 3.2 SystemConfig（系统配置）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 ID |
| configKey | String | 配置键（唯一） |
| configValue | String | 配置值 |
| description | String | 配置说明 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

---

## 4. 前端集成示例

### 4.1 获取直播间列表

```javascript
const response = await fetch('http://localhost:8080/api/channels')
const channels = await response.json()
```

### 4.2 添加直播间

```javascript
const response = await fetch('http://localhost:8080/api/channels', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'cristinavegas',
    displayName: 'Cristina Vegas'
  })
})
const result = await response.json()
```

### 4.3 开始/停止录制

```javascript
// 开始录制
await fetch('http://localhost:8080/api/channels/cristinavegas/start', {
  method: 'POST'
})

// 停止录制
await fetch('http://localhost:8080/api/channels/cristinavegas/stop', {
  method: 'POST'
})
```

### 4.4 获取下载信息

```javascript
const response = await fetch('http://localhost:8080/api/channels/cristinavegas/download-info')
const info = await response.json()
// info.activeDownloads 包含当前活跃的下载线程
```

---

## 5. 错误码说明

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 | 成功 |
| 400 | 请求参数错误（如用户名为空、直播间已存在） |
| 404 | 资源不存在（如直播间 ID 不存在） |
| 500 | 服务器内部错误 |

---

## 6. 注意事项

1. **Cookie 配置**: 需要配置有效的 `cf_clearance` cookie，否则无法访问 Chaturbate API。Cookie 会过期，需定期更新。
2. **IP绑定**: Cloudflare验证绑定IP，获取cookie的IP需要与服务器IP一致。
3. **ffmpeg 依赖**: 录制功能依赖 ffmpeg，请确保系统已安装并配置 `ffmpeg_path`。
4. **数据库**: 使用 H2 嵌入式数据库，文件位于 `./data/dvr.mv.db`。
5. **CORS 配置**: 已允许所有来源跨域请求，生产环境建议限制。
6. **录制路径**: 支持占位符 `{username}` 和 `{yyyy-mm-dd}`。

---

## 7. 完整示例

```javascript
// 1. 添加直播间
const channel = await fetch('http://localhost:8080/api/channels', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'cristinavegas' })
})
const { data } = await channel.json()

// 2. 开始录制
await fetch(`http://localhost:8080/api/channels/${data.username}/start`, {
  method: 'POST'
})

// 3. 查看下载信息
const info = await fetch(`http://localhost:8080/api/channels/${data.username}/download-info`)
const downloadInfo = await info.json()
console.log('Part数量:', downloadInfo.partCount)

// 4. 停止录制（会自动合并并清理临时文件）
await fetch(`http://localhost:8080/api/channels/${data.username}/stop`, {
  method: 'POST'
})
```

---

## 8. 相关资源

- **前端页面**: http://localhost:8080/app/
- **H2 控制台**: http://localhost:8080/h2-console
- **录制文件**: `./recordings/` 目录

---

**文档版本**: v2.0
**更新时间**: 2026-06-02
**项目**: Chaturbate DVR (Spring Boot 3.2.5 + Vue3 + H2 + ffmpeg)
