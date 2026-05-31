package com.chaturbate.dvr.service;

import com.chaturbate.dvr.entity.SystemConfig;
import com.chaturbate.dvr.mapper.SystemConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统配置服务类
 */
@Service
@Slf4j
public class SystemConfigService {
    
    @Autowired
    private SystemConfigMapper systemConfigMapper;
    
    /**
     * 根据配置键获取配置值
     * @param key 配置键
     * @return 配置值，如果不存在返回 null
     */
    public String getConfigValue(String key) {
        SystemConfig config = systemConfigMapper.selectByKey(key);
        return config != null ? config.getConfigValue() : null;
    }
    
    /**
     * 根据配置键获取配置值（带默认值）
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，如果不存在返回默认值
     */
    public String getConfigValue(String key, String defaultValue) {
        String value = getConfigValue(key);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 根据配置键获取整数值
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 整数值，如果不存在或解析失败返回默认值
     */
    public int getConfigValueAsInt(String key, int defaultValue) {
        String value = getConfigValue(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 '{}' 无法解析为整数，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * 设置配置值（如果不存在则创建）
     * @param key 配置键
     * @param value 配置值
     * @param description 描述（仅创建时有效）
     */
    public void setConfigValue(String key, String value, String description) {
        SystemConfig config = systemConfigMapper.selectByKey(key);
        
        if (config != null) {
            // 更新
            systemConfigMapper.updateByKey(key, value);
            log.info("更新配置: {} = {}", key, value);
        } else {
            // 创建
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            systemConfigMapper.insert(config);
            log.info("创建配置: {} = {}", key, value);
        }
    }
    
    /**
     * 获取所有配置
     */
    public List<SystemConfig> getAllConfigs() {
        return systemConfigMapper.selectAll();
    }
    
    /**
     * 删除配置
     * @param key 配置键
     * @return 是否删除成功
     */
    public boolean deleteConfig(String key) {
        int rows = systemConfigMapper.deleteByKey(key);
        if (rows > 0) {
            log.info("删除配置: {}", key);
            return true;
        }
        return false;
    }
}
