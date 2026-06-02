package com.chaturbate.dvr.entity;

import java.time.LocalDateTime;

/**
 * 直播间实体（简化版）
 * 只保留数据库必要字段，状态信息从内存获取
 */
public class Channel {
    
    /** 主键ID */
    private Long id;
    
    /** 主播用户名 */
    private String username;
    
    /** 显示名称 */
    private String displayName;
    
    /** 是否启用监控 */
    private Boolean enabled;
    
    /** 创建时间 */
    private LocalDateTime createdAt;
    
    /** 更新时间 */
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
