package com.chaturbate.dvr.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 录制记录实体
 */
@Data
public class Recording {
    
    /** 主键ID */
    private Long id;
    
    /** 直播间ID */
    private Long channelId;
    
    /** 主播用户名 */
    private String channelUsername;
    
    /** 开始时间 */
    private LocalDateTime startTime;
    
    /** 结束时间 */
    private LocalDateTime endTime;
    
    /** 录制时长(秒) */
    private Integer durationSeconds;
    
    /** 文件路径 */
    private String filePath;
    
    /** 文件大小(字节) */
    private Long fileSize;
    
    /** 文件格式(ts/mp4) */
    private String fileFormat;
    
    /** 录制质量 */
    private String quality;
    
    /** 状态: recording/completed/failed */
    private String status;
    
    /** 错误信息 */
    private String errorMessage;
    
    /** 创建时间 */
    private LocalDateTime createdAt;
}
