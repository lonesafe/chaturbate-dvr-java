package com.chaturbate.dvr.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统设置实体类
 */
@Data
public class Setting {
    private Long id;
    private String settingKey;
    private String settingValue;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
