package com.chaturbate.dvr.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 录制片段实体
 */
@Data
public class RecordingSegment {
    
    /** 主键ID */
    private Long id;
    
    /** 录制记录ID */
    private Long recordingId;
    
    /** 片段序号 */
    private Integer segmentIndex;
    
    /** 片段文件路径 */
    private String filePath;
    
    /** 片段大小(字节) */
    private Long fileSize;
    
    /** 片段时长(秒) */
    private BigDecimal durationSeconds;
    
    /** 开始时间 */
    private LocalDateTime startTime;
    
    /** 结束时间 */
    private LocalDateTime endTime;
    
    /** 创建时间 */
    private LocalDateTime createdAt;
}
