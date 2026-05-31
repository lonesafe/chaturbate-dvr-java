package com.chaturbate.dvr.mapper;

import com.chaturbate.dvr.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统配置 Mapper 接口
 * 对应 XML: src/main/resources/mapper/SystemConfigMapper.xml
 */
@Mapper
public interface SystemConfigMapper {
    
    /**
     * 根据配置键获取配置
     */
    SystemConfig selectByKey(@Param("key") String key);
    
    /**
     * 根据配置键更新配置值
     */
    int updateByKey(@Param("key") String key, @Param("value") String value);
    
    /**
     * 插入新配置
     */
    int insert(SystemConfig config);
    
    /**
     * 查询所有配置
     */
    List<SystemConfig> selectAll();
    
    /**
     * 根据配置键删除
     */
    int deleteByKey(@Param("key") String key);
}
