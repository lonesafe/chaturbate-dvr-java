package com.chaturbate.dvr.controller;

import com.chaturbate.dvr.entity.Recording;
import com.chaturbate.dvr.mapper.RecordingMapper;
import com.chaturbate.dvr.service.HlsRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 录制记录控制器
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RecordingController {

    private final RecordingMapper recordingMapper;
    private final HlsRecorder hlsRecorder;

    /**
     * 获取所有录制记录
     */
    @GetMapping
    public ResponseEntity<List<Recording>> listAll() {
        return ResponseEntity.ok(recordingMapper.selectAll());
    }

    /**
     * 获取直播间的录制记录
     */
    @GetMapping("/channel/{channelId}")
    public ResponseEntity<List<Recording>> listByChannel(@PathVariable Long channelId) {
        return ResponseEntity.ok(recordingMapper.selectByChannelId(channelId));
    }

    /**
     * 获取正在进行的录制
     */
    @GetMapping("/active")
    public ResponseEntity<List<Recording>> listActive() {
        return ResponseEntity.ok(recordingMapper.selectRecording());
    }

    /**
     * 获取录制详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Recording recording = recordingMapper.selectById(id);
        if (recording == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(recording);
    }

    /**
     * 删除录制记录
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRecording(@PathVariable Long id) {
        recordingMapper.deleteById(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "删除成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取 ffmpeg 录制日志
     * @param username 主播用户名
     */
    @GetMapping("/logs/{username}")
    public ResponseEntity<?> getFfmpegLogs(@PathVariable String username) {
        HlsRecorder.RecordingTask task = hlsRecorder.getRecordingTask(username);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("username", username);
        
        if (task == null) {
            // 没有录制任务，返回空日志
            result.put("logs", new ArrayList<>());
            result.put("isRunning", false);
            result.put("message", "没有正在进行的录制任务");
            return ResponseEntity.ok(result);
        }

        List<String> logs = task.getLogs();
        result.put("logs", logs);
        result.put("isRunning", !task.isStopped());
        return ResponseEntity.ok(result);
    }
}
