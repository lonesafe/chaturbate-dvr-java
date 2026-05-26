# Chaturbate DVR - 直播录制系统

基于 Spring Boot + MyBatis + MySQL + Vue3 + Element Plus 的直播录制管理系统。

## 技术栈

### 后端
- **Spring Boot 3.2.5** - Web框架
- **MyBatis** - ORM框架 (SQL写在XML中)
- **MySQL 8.0** - 数据库
- **Apache HttpClient 5** - HTTP客户端
- **Lombok** - 简化代码
- **Jackson** - JSON处理

### 前端
- **Vue 3** - 前端框架
- **Element Plus** - UI组件库
- **Pinia** - 状态管理
- **Vue Router** - 路由管理
- **Axios** - HTTP客户端
- **Day.js** - 日期处理
- **Vite** - 构建工具

## 功能特性

- ✅ 直播间状态监控 (public/private/offline)
- ✅ 自动开始/停止录制
- ✅ HLS流下载 (支持多质量选择)
- ✅ 录制片段管理
- ✅ Web管理界面
- ✅ 实时状态显示
- ✅ 支持Cloudflare验证 (cf_clearance cookie)

## 项目结构

```
chaturbate-dvr-java/
├── pom.xml                          # Maven配置
├── src/
│   ├── main/
│   │   ├── java/com/chaturbate/dvr/
│   │   │   ├── DvrApplication.java              # 启动类
│   │   │   ├── config/                          # 配置类
│   │   │   ├── controller/                      # REST API控制器
│   │   │   ├── service/                         # 业务逻辑
│   │   │   ├── mapper/                          # MyBatis Mapper接口
│   │   │   ├── entity/                          # 实体类
│   │   │   ├── dto/                             # 数据传输对象
│   │   │   ├── task/                            # 定时任务
│   │   │   └── utils/                           # 工具类
│   │   ├── resources/
│   │   │   ├── mapper/                          # MyBatis XML映射文件
│   │   │   ├── static/                          # 静态资源
│   │   │   ├── application.yml                  # 应用配置
│   │   │   └── schema.sql                       # 数据库脚本
│   └── test/                                    # 测试代码
├── frontend/                        # Vue3前端项目
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── api/                     # API接口
│       ├── stores/                  # Pinia状态管理
│       ├── router/                  # 路由配置
│       ├── views/                   # 页面视图
│       └── components/              # 组件
└── recordings/                      # 录制文件目录(运行时创建)
```

## 快速开始

### 1. 数据库准备

```sql
-- 创建数据库
CREATE DATABASE chaturbate_dvr CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 执行 schema.sql 创建表
```

### 2. 配置Cookie

编辑 `src/main/resources/application.yml`:

```yaml
dvr:
  cookie: "cf_clearance=你的cookie值"
  user-agent: "你的User-Agent"
```

### 3. 运行后端

```bash
# 编译运行
mvn spring-boot:run

# 或打包后运行
mvn clean package
java -jar target/chaturbate-dvr-1.0.0.jar
```

### 4. 运行前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:3000

### 5. 生产部署

```bash
# 构建前端
cd frontend
npm run build

# 前端构建后会输出到 src/main/resources/static/app
# 然后打包整个Spring Boot应用
mvn clean package

# 运行
java -jar target/chaturbate-dvr-1.0.0.jar
```

## API接口

### 直播间管理
- `GET /api/channels` - 获取所有直播间
- `GET /api/channels/enabled` - 获取启用的直播间
- `GET /api/channels/recording` - 获取正在录制的直播间
- `POST /api/channels` - 添加直播间
- `PUT /api/channels/{id}` - 更新直播间
- `DELETE /api/channels/{id}` - 删除直播间
- `PUT /api/channels/{id}/toggle` - 启用/禁用直播间

### 录制记录
- `GET /api/recordings` - 获取所有录制记录
- `GET /api/recordings/channel/{channelId}` - 获取直播间的录制记录
- `GET /api/recordings/active` - 获取正在进行的录制
- `GET /api/recordings/{id}` - 获取录制详情
- `DELETE /api/recordings/{id}` - 删除录制记录

## HLS流格式说明

API返回的 `hls_source` 是主播放列表(Master Playlist)，格式如下:

```m3u8
#EXTM3U
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS

#EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,FRAME-RATE=30.000,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio_aac_96"
/v1/edge/.../chunklist_0_video_..._llhls.m3u8?session=...

#EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080,FRAME-RATE=30.000,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio_aac_128"
/v1/edge/.../chunklist_4_video_..._llhls.m3u8?session=...
```

系统会自动:
1. 解析主播放列表获取所有可用质量
2. 根据配置选择优先质量 (默认720p)
3. 下载选定质量的片段列表
4. 循环下载TS片段并合并到文件

## 注意事项

1. **Cookie有效期**: cf_clearance cookie会过期，需要定期更新
2. **IP绑定**: Cloudflare验证绑定IP，获取cookie的IP需要与服务器IP一致
3. **磁盘空间**: 录制文件较大，注意监控磁盘空间
4. **法律合规**: 请确保录制行为符合当地法律法规

## 许可证

MIT License
