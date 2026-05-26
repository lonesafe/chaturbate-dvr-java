# Chaturbate DVR API 接口文档

## 基础信息

- **基础路径**: `http://localhost:8080/api`
- **内容类型**: `application/json`
- **跨域支持**: 已开启（允许所有来源）

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
    "recording": false,
    "lastStatus": "public",
    "lastCheckTime": "2026-05-23T23:00:00",
    "createdAt": "2026-05-23T20:00:00",
    "updatedAt": "2026-05-23T23:00:00"
  }
]
```

### 1.2 获取启用的直播间

- **接口**: `GET /api/channels/enabled`
- **描述**: 获取所有启用监控的直播间
- **响应**: 同 1.1

### 1.3 获取正在录制的直播间

- **接口**: `GET /api/channels/recording`
- **描述**: 获取所有正在录制的直播间
- **响应**: 同 1.1

### 1.4 添加直播间

- **接口**: `POST /api/channels`
- **描述**: 添加新的直播间到监控列表
- **请求体**:
```json
{
  "username": "cristinavegas",     // 必填：主播用户名
  "displayName": "Cristina Vegas" // 可选：显示名称
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
    "enabled": true,
    "recording": false
  }
}
```
- **错误响应** (400):
  - `"用户名不能为空"`
  - `"直播间已存在"`

### 1.5 更新直播间

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

### 1.6 启用/禁用直播间

- **接口**: `PUT /api/channels/{id}/toggle`
- **描述**: 切换直播间的启用状态
- **路径参数**:
  - `id` (Long): 直播间 ID
- **响应示例**:
```json
{
  "success": true,
  "enabled": true  // 切换后的状态
}
```

### 1.7 删除直播间

- **接口**: `DELETE /api/channels/{id}`
- **描述**: 删除直播间（同时停止录制）
- **路径参数**:
  - `id` (Long): 直播间 ID
- **响应示例**:
```json
{
  "success": true,
  "message": "删除成功"
}
```

---

## 2. 录制记录接口

**基础路径**: `/api/recordings`

### 2.1 获取所有录制记录

- **接口**: `GET /api/recordings`
- **描述**: 获取所有录制记录（按开始时间倒序）
- **响应示例**:
```json
[
  {
    "id": 1,
    "channelId": 1,
    "channelUsername": "cristinavegas",
    "startTime": "2026-05-23T22:00:00",
    "endTime": "2026-05-23T23:00:00",
    "durationSeconds": 3600,
    "quality": "720p",
    "status": "completed",
    "fileSize": 1073741824,
    "filePath": "/recordings/cristinavegas/cristinavegas_720p_20260523_220000.mp4"
  }
]
```

**状态说明**:
- `recording`: 录制中
- `completed`: 已完成
- `failed`: 失败

### 2.2 获取指定直播间的录制记录

- **接口**: `GET /api/recordings/channel/{channelId}`
- **描述**: 获取指定直播间的所有录制记录
- **路径参数**:
  - `channelId` (Long): 直播间 ID
- **响应**: 同 2.1

### 2.3 获取正在进行的录制

- **接口**: `GET /api/recordings/active`
- **描述**: 获取所有正在录制的记录
- **响应**: 同 2.1（仅返回 `status=recording` 的记录）

### 2.4 获取录制详情

- **接口**: `GET /api/recordings/{id}`
- **描述**: 获取单条录制记录的详细信息
- **路径参数**:
  - `id` (Long): 录制记录 ID
- **响应**: 同 2.1（单条记录）
- **错误响应** (404): 记录不存在

### 2.5 删除录制记录

- **接口**: `DELETE /api/recordings/{id}`
- **描述**: 删除录制记录（不删除文件）
- **路径参数**:
  - `id` (Long): 录制记录 ID
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
| displayName | String | 显示名称（可选） |
| enabled | Boolean | 是否启用监控 |
| recording | Boolean | 是否正在录制 |
| lastStatus | String | 最后状态：`public`/`private`/`offline` |
| lastCheckTime | LocalDateTime | 最后检查时间 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

### 3.2 Recording（录制记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 ID |
| channelId | Long | 关联的直播间 ID |
| channelUsername | String | 主播用户名（冗余字段，便于查询） |
| startTime | LocalDateTime | 录制开始时间 |
| endTime | LocalDateTime | 录制结束时间 |
| durationSeconds | Integer | 录制时长（秒） |
| quality | String | 录制质量（如 `720p`） |
| status | String | 状态：`recording`/`completed`/`failed` |
| fileSize | Long | 文件大小（字节） |
| filePath | String | 文件路径 |

---

## 4. 前端集成示例

### 4.1 使用 Axios 调用接口

```javascript
import axios from 'axios'

const API_BASE = 'http://localhost:8080/api'

// 获取所有直播间
const fetchChannels = async () => {
  const res = await axios.get(`${API_BASE}/channels`)
  return res.data
}

// 添加直播间
const addChannel = async (username, displayName) => {
  const res = await axios.post(`${API_BASE}/channels`, {
    username,
    displayName
  })
  return res.data
}

// 启用/禁用直播间
const toggleChannel = async (id) => {
  const res = await axios.put(`${API_BASE}/channels/${id}/toggle`)
  return res.data
}

// 删除直播间
const deleteChannel = async (id) => {
  const res = await axios.delete(`${API_BASE}/channels/${id}`)
  return res.data
}

// 获取录制记录
const fetchRecordings = async () => {
  const res = await axios.get(`${API_BASE}/recordings`)
  return res.data
}
```

### 4.2 使用 Fetch API

```javascript
// 获取所有直播间
fetch('http://localhost:8080/api/channels')
  .then(res => res.json())
  .then(data => console.log(data))
  .catch(err => console.error(err))
```

---

## 5. 错误码说明

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 | 成功 |
| 400 | 请求参数错误（如用户名为空） |
| 404 | 资源不存在（如直播间 ID 不存在） |
| 500 | 服务器内部错误 |

---

## 6. 注意事项

1. **Cookie 配置**: 需要在 `application.yml` 中配置有效的 `cf_clearance` cookie，否则无法访问 Chaturbate API。
2. **User-Agent**: 需要配置真实的浏览器 User-Agent，避免被反爬机制拦截。
3. **ffmpeg 依赖**: 录制功能依赖 ffmpeg，请确保系统已安装 ffmpeg 并配置 `ffmpeg-path`（默认使用系统 PATH 中的 ffmpeg）。
4. **数据库初始化**: 首次运行会自动创建数据库表结构（通过 `schema.sql`）。
5. **CORS 配置**: 已允许所有来源跨域请求，生产环境建议限制具体域名。

---

## 7. 完整示例：添加直播间并开始录制

```javascript
// 1. 添加直播间
const channel = await axios.post('http://localhost:8080/api/channels', {
  username: 'cristinavegas',
  displayName: 'Cristina Vegas'
})

console.log('添加成功:', channel.data)

// 2. 启用监控（会自动开始录制）
await axios.put(`http://localhost:8080/api/channels/${channel.data.data.id}/toggle`)

// 3. 查看录制记录
const recordings = await axios.get('http://localhost:8080/api/recordings/active')
console.log('正在录制:', recordings.data)
```

---

## 8. 相关资源

- **前端页面**: 
  - Vue3 版本：`http://localhost:8080/app/`
  - 简单 HTML 版本：`http://localhost:8080/index.html`
- **API 测试工具**: 推荐使用 Postman 或 curl 测试接口
- **日志查看**: 录制过程中的错误会记录在应用日志中

---

**文档版本**: v1.0  
**更新时间**: 2026-05-23  
**项目**: Chaturbate DVR (Spring Boot 3.2.5 + Vue3 + ffmpeg)
