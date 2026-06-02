-- Chaturbate DVR Database Schema
-- MySQL 8.0+
#
# CREATE DATABASE IF NOT EXISTS chaturbate_dvr
#     CHARACTER SET utf8mb4
#     COLLATE utf8mb4_unicode_ci;

USE chaturbate_dvr;

-- ============================================
-- Table: channel (直播间配置)
-- ============================================
CREATE TABLE IF NOT EXISTS channel (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username        VARCHAR(100) NOT NULL UNIQUE COMMENT '主播用户名',
    display_name    VARCHAR(200) COMMENT '显示名称',
    is_enabled      TINYINT(1) DEFAULT 1 COMMENT '是否启用监控(1=启用,0=禁用)',
    is_recording    TINYINT(1) DEFAULT 0 COMMENT '是否正在录制',
    last_status     VARCHAR(50) COMMENT '最后状态(public/private/offline)',
    last_check_time DATETIME COMMENT '最后检查时间',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_enabled (is_enabled),
    INDEX idx_recording (is_recording)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直播间配置表';

-- ============================================
-- Table: system_config (系统配置)
-- ============================================
CREATE TABLE IF NOT EXISTS system_config (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    config_key  VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置说明',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- ============================================
-- Insert default system configs
-- ============================================
INSERT INTO system_config (config_key, config_value, description) VALUES
('cookie', '', 'Cloudflare cf_clearance cookie'),
('user_agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36', '浏览器 User-Agent'),
('check_interval_seconds', '30', '直播间状态检查间隔(秒)'),
('record_path', './recordings', '录制文件保存路径'),
('preferred_quality', '720p', '优先录制质量(360p/480p/540p/720p/1080p)'),
('api_base_url', 'https://zh-hans.chaturbate.com/api/chatvideocontext/', 'Chaturbate API 基础URL'),
('tmp_path', './tmp', '临时文件目录'),
('segment_duration_seconds', '10', 'HLS 片段时长(秒)'),
('download_threads', '4', '并发下载线程数'),
('merge_segments', '10', '每N个片段触发一次合并'),
('ffmpeg_path', 'ffmpeg', 'ffmpeg 可执行文件路径')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
