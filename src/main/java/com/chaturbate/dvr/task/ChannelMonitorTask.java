package com.chaturbate.dvr.task;

import com.chaturbate.dvr.dto.ChatVideoContext;
import com.chaturbate.dvr.entity.Channel;
import com.chaturbate.dvr.mapper.ChannelMapper;
import com.chaturbate.dvr.service.ChaturbateApiService;
import com.chaturbate.dvr.service.HlsRecorder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 直播间监控定时任务
 * 定期检查频道状态，自动开始/停止录制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMonitorTask {

    private final ChaturbateApiService apiService;
    private final HlsRecorder hlsRecorder;
    private final ChannelMapper channelMapper;

    /** 启用的频道内存列表（从数据库加载） */
    private final CopyOnWriteArrayList<Channel> enabledChannels = new CopyOnWriteArrayList<>();
    
    /** 频道状态映射（username -> roomStatus），由定时任务更新 */
    private final ConcurrentHashMap<String, String> channelStatuses = new ConcurrentHashMap<>();

    /**
     * 启动时从数据库加载启用的频道
     */
    @PostConstruct
    public void loadEnabledChannels() {
        refreshEnabledChannels();
    }

    /**
     * 刷新启用的频道列表（供外部调用）
     */
    public void refreshEnabledChannels() {
        List<Channel> channels = channelMapper.selectAllEnabled();
        enabledChannels.clear();
        enabledChannels.addAll(channels);
        log.info("已加载 {} 个启用的直播间", enabledChannels.size());
    }

    /**
     * 定期检查频道状态 (默认每30秒)
     */
    @Scheduled(fixedDelayString = "${dvr.check-interval-seconds:30}000")
    public void checkChannels() {
        if (enabledChannels.isEmpty()) {
            return;
        }

        log.debug("开始检查 {} 个直播间状态", enabledChannels.size());

        for (Channel channel : enabledChannels) {
            try {
                checkChannel(channel);
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("检查直播间 [{}] 时发生异常: {}", channel.getUsername(), e.getMessage());
            }
        }
    }

    /**
     * 检查单个直播间
     */
    private void checkChannel(Channel channel) {
        String username = channel.getUsername();
        ChatVideoContext context = apiService.getChatVideoContext(username);

        if (context == null) {
            log.warn("无法获取直播间 [{}] 信息", username);
            return;
        }

        // 更新频道状态到内存映射
        channelStatuses.put(username, context.getRoomStatus());
        
        boolean isPublic = context.isPublicLive();
        boolean isRecording = hlsRecorder.isRecording(username);

        log.debug("直播间 [{}] 状态: {}, 录制中: {}", username, context.getRoomStatus(), isRecording);

        if (isPublic && !isRecording) {
            // 开始录制
            startRecording(channel);
        } else if (!isPublic && isRecording) {
            // 停止录制
            stopRecording(channel);
        }
    }

    /**
     * 开始录制
     */
    private void startRecording(Channel channel) {
        String username = channel.getUsername();
        String hlsSource = apiService.getHlsSource(username);

        // 开始录制（HlsRecorder 会自动维护录制中的列表）
        String taskId = hlsRecorder.startRecording(username, hlsSource);

        log.info("直播间 [{}] 开始录制, 任务ID: {}", username, taskId);
    }

    /**
     * 停止录制
     */
    private void stopRecording(Channel channel) {
        String username = channel.getUsername();

        // 停止录制器（会自动从录制中列表移除）
        hlsRecorder.stopRecording(username);

        log.info("直播间 [{}] 停止录制", username);
    }

    /**
     * 获取所有频道的当前状态
     */
    public Map<String, String> getChannelStatuses() {
        return new HashMap<>(channelStatuses);
    }

}
