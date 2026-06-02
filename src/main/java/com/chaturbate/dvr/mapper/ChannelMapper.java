package com.chaturbate.dvr.mapper;

import com.chaturbate.dvr.entity.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 直播间 Mapper
 */
@Mapper
public interface ChannelMapper {
    
    /**
     * 根据ID查询
     */
    Channel selectById(@Param("id") Long id);
    
    /**
     * 根据用户名查询
     */
    Channel selectByUsername(@Param("username") String username);
    
    /**
     * 查询所有启用的直播间
     */
    List<Channel> selectAllEnabled();
    
    /**
     * 查询所有直播间
     */
    List<Channel> selectAll();
    
    /**
     * 插入
     */
    int insert(Channel channel);
    
    /**
     * 更新
     */
    int update(Channel channel);
    
    /**
     * 删除
     */
    int deleteById(@Param("id") Long id);
}
