-- Chaturbate DVR Database Schema
-- H2 Database
--

-- ============================================
-- Table: channel (直播间配置 - 简化版)
-- 注意：直播间状态不再存储到数据库，通过内存管理
-- ============================================
CREATE TABLE IF NOT EXISTS channel
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username     VARCHAR(100) NOT NULL UNIQUE COMMENT '主播用户名',
    display_name VARCHAR(200) COMMENT '显示名称',
    is_enabled   INTEGER   DEFAULT 1 COMMENT '是否启用',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
);

CREATE INDEX IF NOT EXISTS idx_enabled ON channel (is_enabled);

-- ============================================
-- Table: system_config (系统配置)
-- ============================================
CREATE TABLE IF NOT EXISTS system_config
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    config_key   VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    description  VARCHAR(500) COMMENT '配置说明',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- ============================================
-- Insert default system configs
-- ============================================
MERGE INTO system_config (config_key, config_value, description) KEY (config_key) VALUES ('cookie', '',
                                                                                          'Cloudflare cf_clearance cookie'),
                                                                                         ('user_agent',
                                                                                          'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36',
                                                                                          '浏览器 User-Agent'),
                                                                                         ('check_interval_seconds',
                                                                                          '30',
                                                                                          '直播间状态检查间隔(秒)'),
                                                                                         ('record_path',
                                                                                          './recordings/{username}/{username}-{yyyy-mm-dd}.mp4',
                                                                                          '录制文件保存路径'),
                                                                                         ('preferred_quality', '720p',
                                                                                          '优先录制质量(360p/480p/540p/720p/1080p)'),
                                                                                         ('api_base_url',
                                                                                          'https://zh-hans.chaturbate.com/api/chatvideocontext/',
                                                                                          'Chaturbate API 基础URL'),
                                                                                         ('tmp_path', './tmp',
                                                                                          '临时文件目录'),
                                                                                         ('segment_duration_seconds',
                                                                                          '10', 'HLS 片段时长(秒)'),
                                                                                         ('download_threads', '4',
                                                                                          '并发下载线程数'),
                                                                                         ('ffmpeg_path', 'ffmpeg',
                                                                                          'ffmpeg 可执行文件路径');
