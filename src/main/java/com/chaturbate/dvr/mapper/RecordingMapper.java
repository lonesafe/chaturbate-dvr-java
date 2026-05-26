package com.chaturbate.dvr.mapper;

import com.chaturbate.dvr.entity.Recording;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 录制记录 Mapper
 */
@Mapper
public interface RecordingMapper {
    
    /**
     * 根据ID查询
     */
    Recording selectById(@Param("id") Long id);
    
    /**
     * 查询直播间所有录制记录
     */
    List<Recording> selectByChannelId(@Param("channelId") Long channelId);
    
    /**
     * 查询所有录制记录
     */
    List<Recording> selectAll();
    
    /**
     * 查询正在进行的录制
     */
    List<Recording> selectRecording();
    
    /**
     * 插入
     */
    int insert(Recording recording);
    
    /**
     * 更新录制完成
     */
    int updateComplete(@Param("id") Long id,
                       @Param("endTime") java.time.LocalDateTime endTime,
                       @Param("durationSeconds") Integer durationSeconds,
                       @Param("fileSize") Long fileSize,
                       @Param("status") String status);
    
    /**
     * 更新状态
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);
    
    /**
     * 更新错误信息
     */
    int updateError(@Param("id") Long id, @Param("errorMessage") String errorMessage);
    
    /**
     * 删除
     */
    int deleteById(@Param("id") Long id);
}
