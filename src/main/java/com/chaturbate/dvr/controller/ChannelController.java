package com.chaturbate.dvr.controller;

import com.chaturbate.dvr.entity.Channel;
import com.chaturbate.dvr.mapper.ChannelMapper;
import com.chaturbate.dvr.service.ChaturbateApiService;
import com.chaturbate.dvr.service.HlsRecorder;
import com.chaturbate.dvr.task.ChannelMonitorTask;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 直播间管理控制器
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChannelController {

    private final ChannelMapper channelMapper;
    private final HlsRecorder hlsRecorder;
    private final ChaturbateApiService apiService;
    private final ChannelMonitorTask channelMonitorTask;

    /**
     * 获取所有直播间
     */
    @GetMapping
    public ResponseEntity<List<Channel>> listAll() {
        return ResponseEntity.ok(channelMapper.selectAll());
    }

    /**
     * 获取启用的直播间
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<Channel>> listEnabled() {
        return ResponseEntity.ok(channelMapper.selectAllEnabled());
    }

    /**
     * 获取正在录制的直播间（从内存获取）
     */
    @GetMapping("/recording")
    public ResponseEntity<?> listRecording() {
        List<String> recordingUsers = hlsRecorder.getRecordingUsernames();
        List<Channel> recordingChannels = new ArrayList<>();
        for (String username : recordingUsers) {
            Channel channel = channelMapper.selectByUsername(username);
            if (channel != null) {
                recordingChannels.add(channel);
            }
        }
        return ResponseEntity.ok(recordingChannels);
    }

    /**
     * 获取所有频道的当前状态（从内存获取）
     */
    @GetMapping("/statuses")
    public ResponseEntity<Map<String, String>> getChannelStatuses() {
        return ResponseEntity.ok(channelMonitorTask.getChannelStatuses());
    }

    /**
     * 添加直播间
     */
    @PostMapping
    public ResponseEntity<?> addChannel(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String displayName = request.get("displayName");

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("用户名不能为空");
        }

        // 检查是否已存在
        Channel existing = channelMapper.selectByUsername(username);
        if (existing != null) {
            return ResponseEntity.badRequest().body("直播间已存在");
        }

        Channel channel = new Channel();
        channel.setUsername(username.trim());
        channel.setDisplayName(displayName != null ? displayName : username);
        channel.setEnabled(true);

        channelMapper.insert(channel);

        // 刷新内存中的频道列表，确保新频道立即被监控
        channelMonitorTask.refreshEnabledChannels();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "添加成功");
        result.put("data", channel);
        return ResponseEntity.ok(result);
    }

    /**
     * 批量添加直播间
     * @param request 包含 "usernames" 字段，支持：
     *                 1. JSON数组：["user1", "user2"]
     *                 2. 逗号分隔字符串："user1,user2,user3"
     *                 3. 换行分隔字符串："user1\nuser2\nuser3"
     */
    @PostMapping("/batch")
    public ResponseEntity<?> batchAddChannels(@RequestBody Map<String, Object> request) {
        List<String> usernames = new ArrayList<>();

        Object usernamesObj = request.get("usernames");
        if (usernamesObj == null) {
            return ResponseEntity.badRequest().body("usernames 不能为空");
        }

        if (usernamesObj instanceof List) {
            // JSON 数组
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) usernamesObj;
            usernames.addAll(list);
        } else if (usernamesObj instanceof String) {
            String str = (String) usernamesObj;
            // 优先按换行分割，其次按逗号分割
            if (str.contains("\n")) {
                String[] parts = str.split("[\\n\\r]+");
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        usernames.add(trimmed);
                    }
                }
            } else {
                String[] parts = str.split(",");
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        usernames.add(trimmed);
                    }
                }
            }
        }

        if (usernames.isEmpty()) {
            return ResponseEntity.badRequest().body("usernames 不能为空");
        }

        List<Map<String, Object>> successList = new ArrayList<>();
        List<String> skipList = new ArrayList<>();
        List<String> failList = new ArrayList<>();

        for (String username : usernames) {
            String u = username.trim();
            if (u.isEmpty()) continue;

            Channel existing = channelMapper.selectByUsername(u);
            if (existing != null) {
                skipList.add(u);
                continue;
            }

            try {
                Channel channel = new Channel();
                channel.setUsername(u);
                channel.setDisplayName(u);
                channel.setEnabled(true);
                channelMapper.insert(channel);

                Map<String, Object> item = new HashMap<>();
                item.put("username", u);
                item.put("displayName", u);
                successList.add(item);
            } catch (Exception e) {
                failList.add(u + " (" + e.getMessage() + ")");
            }
        }

        // 统一刷新一次内存列表
        if (!successList.isEmpty()) {
            channelMonitorTask.refreshEnabledChannels();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("total", usernames.size());
        result.put("added", successList.size());
        result.put("skipped", skipList.size());
        result.put("failed", failList.size());
        result.put("addedList", successList);
        result.put("skippedList", skipList);
        result.put("failedList", failList);
        return ResponseEntity.ok(result);
    }

    /**
     * 更新直播间
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateChannel(@PathVariable Long id, @RequestBody Channel channel) {
        Channel existing = channelMapper.selectById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setId(id);
        channelMapper.update(channel);

        // 刷新内存中的频道列表
        channelMonitorTask.refreshEnabledChannels();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除直播间（如果正在录制，先停止）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteChannel(@PathVariable Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        // 如果正在录制，先停止
        if (hlsRecorder.isRecording(channel.getUsername())) {
            hlsRecorder.stopRecording(channel.getUsername());
        }

        channelMapper.deleteById(id);

        // 刷新内存中的频道列表
        channelMonitorTask.refreshEnabledChannels();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "删除成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 启用/禁用直播间
     */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggleChannel(@PathVariable Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setEnabled(!channel.getEnabled());
        channelMapper.update(channel);

        // 刷新内存中的频道列表
        channelMonitorTask.refreshEnabledChannels();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("enabled", channel.getEnabled());
        return ResponseEntity.ok(result);
    }

    /**
     * 开始录制
     */
    @PostMapping("/{username}/start")
    public ResponseEntity<?> startRecording(@PathVariable String username) {
        try {
            // 获取直播间信息
            var context = apiService.getChatVideoContext(username);
            if (context == null) {
                return ResponseEntity.badRequest().body("无法获取直播间信息");
            }

            String hlsSource = context.getHlsSource();
            if (hlsSource == null || hlsSource.isEmpty()) {
                return ResponseEntity.badRequest().body("直播间当前没有HLS源");
            }

            // 检查是否已在录制
            if (hlsRecorder.isRecording(username)) {
                return ResponseEntity.badRequest().body("该直播间已在录制中");
            }

            // 开始录制
            String taskId = hlsRecorder.startRecording(username, hlsSource);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "开始录制成功");
            result.put("taskId", taskId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 停止录制（会立即合并未合并的分片）
     */
    @PostMapping("/{username}/stop")
    public ResponseEntity<?> stopRecording(@PathVariable String username) {
        try {
            if (!hlsRecorder.isRecording(username)) {
                return ResponseEntity.badRequest().body("该直播间未在录制");
            }

            // 停止录制（会自动从录制中列表移除）
            hlsRecorder.stopRecording(username);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "停止录制成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取下载信息
     */
    @GetMapping("/{username}/download-info")
    public ResponseEntity<?> getDownloadInfo(@PathVariable String username) {
        return ResponseEntity.ok(hlsRecorder.getDownloadInfo(username));
    }

    /**
     * 获取录制日志
     */
    @GetMapping("/{username}/logs")
    public ResponseEntity<?> getLogs(@PathVariable String username) {
        Map<String, Object> result = new HashMap<>();
        HlsRecorder.RecordingTask task = hlsRecorder.getRecordingTask(username);
        
        if (task == null) {
            result.put("success", true);
            result.put("isRunning", false);
            result.put("logs", Collections.emptyList());
            return ResponseEntity.ok(result);
        }

        result.put("success", true);
        result.put("isRunning", !task.isStopped());
        result.put("logs", task.getLogs());
        return ResponseEntity.ok(result);
    }
}
