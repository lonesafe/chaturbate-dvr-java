package com.chaturbate.dvr.controller;

import com.chaturbate.dvr.entity.Channel;
import com.chaturbate.dvr.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播间管理控制器
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChannelController {

    private final ChannelMapper channelMapper;

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
     * 获取正在录制的直播间
     */
    @GetMapping("/recording")
    public ResponseEntity<List<Channel>> listRecording() {
        return ResponseEntity.ok(channelMapper.selectRecording());
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
        channel.setRecording(false);

        channelMapper.insert(channel);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "添加成功");
        result.put("data", channel);
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

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除直播间
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteChannel(@PathVariable Long id) {
        channelMapper.deleteById(id);

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

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("enabled", channel.getEnabled());
        return ResponseEntity.ok(result);
    }
}
