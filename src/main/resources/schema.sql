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
-- Table: recording (录制记录)
-- ============================================
CREATE TABLE IF NOT EXISTS recording (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    channel_id      BIGINT NOT NULL COMMENT '直播间ID',
    channel_username VARCHAR(100) NOT NULL COMMENT '主播用户名',
    start_time      DATETIME NOT NULL COMMENT '开始时间',
    end_time        DATETIME COMMENT '结束时间',
    duration_seconds INT COMMENT '录制时长(秒)',
    file_path       VARCHAR(500) COMMENT '文件路径',
    file_size       BIGINT COMMENT '文件大小(字节)',
    file_format     VARCHAR(20) DEFAULT 'ts' COMMENT '文件格式(ts/mp4)',
    quality         VARCHAR(20) COMMENT '录制质量(360p/480p/540p/720p/1080p)',
    status          VARCHAR(20) DEFAULT 'recording' COMMENT '状态(recording/completed/failed)',
    error_message   TEXT COMMENT '错误信息',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_channel_id (channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='录制记录表';

-- ============================================
-- Table: recording_segment (录制片段)
-- ============================================
CREATE TABLE IF NOT EXISTS recording_segment (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    recording_id    BIGINT NOT NULL COMMENT '录制记录ID',
    segment_index   INT NOT NULL COMMENT '片段序号',
    file_path       VARCHAR(500) NOT NULL COMMENT '片段文件路径',
    file_size       BIGINT COMMENT '片段大小(字节)',
    duration_seconds DECIMAL(10,3) COMMENT '片段时长(秒)',
    start_time      DATETIME NOT NULL COMMENT '开始时间',
    end_time        DATETIME COMMENT '结束时间',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_recording_id (recording_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='录制片段表';

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
('cookie', 'cf_clearance=...', 'Cloudflare clearance cookie'),
('user_agent', 'Mozilla/5.0 ...', 'Browser User-Agent'),
('check_interval_seconds', '30', '直播间状态检查间隔(秒)'),
('record_path', './recordings', '录制文件保存路径'),
('preferred_quality', '720p', '优先录制质量'),
('api_base_url', 'https://zh-hans.chaturbate.com/api/chatvideocontext/', 'API基础URL')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
