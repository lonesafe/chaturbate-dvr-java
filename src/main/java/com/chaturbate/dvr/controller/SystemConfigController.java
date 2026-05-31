package com.chaturbate.dvr.controller;

import com.chaturbate.dvr.entity.SystemConfig;
import com.chaturbate.dvr.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置控制器
 */
@RestController
@RequestMapping("/api/config")
@Slf4j
public class SystemConfigController {
    
    @Autowired
    private SystemConfigService configService;
    
    /**
     * 获取所有配置
     */
    @GetMapping
    public Map<String, Object> getAllConfigs() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<SystemConfig> configs = configService.getAllConfigs();
            result.put("success", true);
            result.put("data", configs);
        } catch (Exception e) {
            log.error("获取所有配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    /**
     * 根据键获取配置
     */
    @GetMapping("/{key}")
    public Map<String, Object> getConfig(@PathVariable String key) {
        Map<String, Object> result = new HashMap<>();
        try {
            String value = configService.getConfigValue(key);
            if (value != null) {
                result.put("success", true);
                result.put("data", value);
            } else {
                result.put("success", false);
                result.put("message", "配置项不存在: " + key);
            }
        } catch (Exception e) {
            log.error("获取配置失败: " + key, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    /**
     * 更新配置（单个）
     */
    @PutMapping("/{key}")
    public Map<String, Object> updateConfig(
            @PathVariable String key,
            @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String value = body.get("value");
            configService.setConfigValue(key, value, null);
            result.put("success", true);
            result.put("message", "配置更新成功");
        } catch (Exception e) {
            log.error("更新配置失败: " + key, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    /**
     * 批量更新配置
     */
    @PutMapping("/batch")
    public Map<String, Object> batchUpdateConfigs(@RequestBody Map<String, String> configs) {
        Map<String, Object> result = new HashMap<>();
        try {
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                configService.setConfigValue(entry.getKey(), entry.getValue(), null);
            }
            result.put("success", true);
            result.put("message", "批量更新成功");
        } catch (Exception e) {
            log.error("批量更新配置失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    /**
     * 删除配置
     */
    @DeleteMapping("/{key}")
    public Map<String, Object> deleteConfig(@PathVariable String key) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = configService.deleteConfig(key);
            if (success) {
                result.put("success", true);
                result.put("message", "配置删除成功");
            } else {
                result.put("success", false);
                result.put("message", "配置项不存在: " + key);
            }
        } catch (Exception e) {
            log.error("删除配置失败: " + key, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
