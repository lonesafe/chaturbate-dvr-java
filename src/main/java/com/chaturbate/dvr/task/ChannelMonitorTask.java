package com.chaturbate.dvr.task;

import com.chaturbate.dvr.dto.ChatVideoContext;
import com.chaturbate.dvr.entity.Channel;
import com.chaturbate.dvr.entity.Recording;
import com.chaturbate.dvr.mapper.ChannelMapper;
import com.chaturbate.dvr.mapper.RecordingMapper;
import com.chaturbate.dvr.service.ChaturbateApiService;
import com.chaturbate.dvr.service.HlsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 直播间监控定时任务
 * 定期检查直播间状态，自动开始/停止录制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMonitorTask {

    private final ChaturbateApiService apiService;
    private final HlsRecorder hlsRecorder;
    private final ChannelMapper channelMapper;
    private final RecordingMapper recordingMapper;

    @Value("${dvr.preferred-quality:720p}")
    private String preferredQuality;

    /**
     * 定期检查直播间状态 (默认每30秒)
     */
    @Scheduled(fixedDelayString = "${dvr.check-interval-seconds:30}000")
    public void checkChannels() {
        List<Channel> channels = channelMapper.selectAllEnabled();
        if (channels.isEmpty()) {
            return;
        }

        log.debug("开始检查 {} 个直播间状态", channels.size());

        for (Channel channel : channels) {
            try {
                checkChannel(channel);
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

        String roomStatus = context.getRoomStatus();
        boolean isPublic = context.isPublicLive();
        boolean isRecording = hlsRecorder.isRecording(username);

        // 更新最后状态
        channel.setLastStatus(roomStatus);
        channel.setLastCheckTime(LocalDateTime.now());
        channelMapper.update(channel);

        log.debug("直播间 [{}] 状态: {}, 录制中: {}", username, roomStatus, isRecording);

        if (isPublic && !isRecording) {
            // 开始录制
            startRecording(channel, context);
        } else if (!isPublic && isRecording) {
            // 停止录制
            stopRecording(channel);
        }
    }

    /**
     * 开始录制
     */
    private void startRecording(Channel channel, ChatVideoContext context) {
        String username = channel.getUsername();
        String hlsSource = context.getHlsSource();

        if (hlsSource == null || hlsSource.isEmpty()) {
            log.error("直播间 [{}] 没有HLS源地址", username);
            return;
        }

        // 更新频道录制状态
        channel.setRecording(true);
        channelMapper.updateRecordingStatus(channel.getId(), true, "public");

        // 创建录制记录
        Recording recording = new Recording();
        recording.setChannelId(channel.getId());
        recording.setChannelUsername(username);
        recording.setStartTime(LocalDateTime.now());
        recording.setFileFormat("ts");
        recording.setQuality(preferredQuality);
        recording.setStatus("recording");
        recordingMapper.insert(recording);

        // 开始录制
        String taskId = hlsRecorder.startRecording(username, hlsSource);

        log.info("直播间 [{}] 开始录制, 任务ID: {}, HLS: {}", username, taskId, hlsSource);
    }

    /**
     * 停止录制
     */
    private void stopRecording(Channel channel) {
        String username = channel.getUsername();

        // 停止录制器
        hlsRecorder.stopRecording(username);

        // 更新频道状态
        channel.setRecording(false);
        channelMapper.updateRecordingStatus(channel.getId(), false, channel.getLastStatus());

        // 更新录制记录
        List<Recording> recordings = recordingMapper.selectRecording();
        for (Recording recording : recordings) {
            if (recording.getChannelId().equals(channel.getId())) {
                recording.setEndTime(LocalDateTime.now());
                recording.setStatus("completed");
                // TODO: 计算实际时长和文件大小
                recordingMapper.updateComplete(
                    recording.getId(),
                    recording.getEndTime(),
                    0, // duration
                    0L, // fileSize
                    "completed"
                );
                break;
            }
        }

        log.info("直播间 [{}] 停止录制", username);
    }
}
