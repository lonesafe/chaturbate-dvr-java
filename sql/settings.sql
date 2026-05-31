-- 设置表
CREATE TABLE IF NOT EXISTS `settings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `setting_key` varchar(100) NOT NULL COMMENT '设置键',
  `setting_value` text COMMENT '设置值',
  `description` varchar(255) DEFAULT NULL COMMENT '描述',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统设置表';

-- 插入默认设置
INSERT INTO `settings` (`setting_key`, `setting_value`, `description`) VALUES
('dvr.cookie', '', 'Cloudflare cf_clearance Cookie'),
('dvr.user-agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36', 'User-Agent 请求头'),
('dvr.record-path', './recordings', '录制文件保存路径'),
('dvr.tmp-path', './tmp', '临时文件目录'),
('dvr.check-interval-seconds', '30', '直播间状态检查间隔（秒）'),
('dvr.segment-duration-seconds', '10', 'HLS 片段时长（秒）'),
('dvr.preferred-quality', '720p', '首选录制分辨率：360p, 480p, 540p, 720p, 1080p'),
('dvr.download-threads', '4', '并发下载线程数'),
('dvr.merge-segments', '10', '每 N 个片段触发一次合并'),
('dvr.ffmpeg-path', 'ffmpeg', 'ffmpeg 可执行文件路径'),
('dvr.api-base-url', 'https://zh-hans.chaturbate.com/api/chatvideocontext/', 'Chaturbate API 基础 URL');
