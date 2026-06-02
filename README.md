# Chaturbate DVR - 直播录制系统

基于 Spring Boot + MyBatis + H2 + Vue3 + Element Plus 的直播录制管理系统，支持 TS/fMP4 双格式 HLS 流录制。

## 技术栈

### 后端
- **Spring Boot 3.2.5** - Web框架
- **MyBatis** - ORM框架 (XML映射)
- **H2** - 嵌入式文件数据库
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
- ✅ 手动开始/停止录制
- ✅ HLS流下载 (支持 TS 和 fMP4 双格式)
- ✅ 实时追加合并 (边录边合并)
- ✅ 403错误自动刷新m3u8地址
- ✅ Web管理界面
- ✅ 实时状态显示 (内存状态，无需查询数据库)
- ✅ 下载监控弹窗 (实时显示活跃下载线程)
- ✅ 录制路径占位符 (`{username}`、`{yyyy-mm-dd}`)
- ✅ 直播停止后自动清理临时文件
- ✅ 支持Cloudflare验证 (cf_clearance cookie)
- ✅ H2 Web控制台 (`/h2-console`)

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
│   │   └── resources/
│   │       ├── mapper/                          # MyBatis XML映射文件
│   │       ├── static/                          # 静态资源
│   │       ├── templates/                       # HTML模板
│   │       ├── application.yml                  # 应用配置
│   │       └── schema.sql                       # 数据库脚本
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
├── data/                            # H2数据库文件(运行时创建)
├── recordings/                      # 录制文件目录(运行时创建)
└── tmp/                            # 临时文件目录(运行时创建)
```

## 快速开始

### 1. 配置Cookie

编辑数据库 `system_config` 表或通过前端设置：

| config_key | config_value |
|------------|--------------|
| cookie | 你的cf_clearance cookie值 |
| user_agent | 你的浏览器User-Agent |

> **注意**: cf_clearance cookie 绑定来源IP，需在相同IP环境下获取。

### 2. 运行后端

```bash
# 编译打包
mvn clean package -DskipTests

# 运行
java -jar target/chaturbate-dvr-1.0.0.jar
```

访问：
- 前端页面：http://localhost:8080/app/
- H2 控制台：http://localhost:8080/h2-console

### 3. H2 数据库

- JDBC URL：`jdbc:h2:./data/dvr`
- 用户名：`sa`
- 密码：（空）

### 4. 前端开发模式

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

# 打包Spring Boot应用
mvn clean package -DskipTests

# 运行
java -jar target/chaturbate-dvr-1.0.0.jar
```

## 系统配置

通过前端「系统设置」或直接修改数据库 `system_config` 表：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| cookie | - | Cloudflare cf_clearance cookie |
| user_agent | Chrome UA | 浏览器User-Agent |
| check_interval_seconds | 30 | 直播间状态检查间隔 |
| record_path | ./recordings | 录制文件保存路径，支持 `{username}`、`{yyyy-mm-dd}` 占位符 |
| preferred_quality | 720p | 优先录制质量 |
| api_base_url | Chaturbate API | API基础URL |
| tmp_path | ./tmp | 临时文件目录 |
| segment_duration_seconds | 10 | HLS片段时长(秒) |
| download_threads | 4 | 并发下载线程数 |
| ffmpeg_path | ffmpeg | ffmpeg可执行文件路径 |

## HLS流格式说明

Chaturbate 使用两种 HLS 格式：

### TS格式 (MPEG-TS)
- 传统格式，片段为 `.ts` 文件
- 下载后用 ffmpeg concat 合并

### fMP4格式 (Fragmented MP4)
- 现代格式，片段为 `.m4s` 文件
- 包含音视频分离的流
- 每个 part 实时合并为完整MP4后追加到最终文件

**录制流程**：
1. 解析 master.m3u8 获取可用质量
2. 选择优先质量 (默认720p)
3. 下载 chunklist 和分片
4. 每个轮询周期结束后实时合并
5. 直播结束时清理临时文件

## 注意事项

1. **Cookie有效期**: cf_clearance cookie会过期，需要定期更新
2. **IP绑定**: Cloudflare验证绑定IP，获取cookie的IP需要与服务器IP一致
3. **磁盘空间**: 录制文件较大，注意监控磁盘空间
4. **ffmpeg**: 确保系统已安装 ffmpeg 并在 PATH 中
5. **法律合规**: 请确保录制行为符合当地法律法规

## 许可证

MIT License
