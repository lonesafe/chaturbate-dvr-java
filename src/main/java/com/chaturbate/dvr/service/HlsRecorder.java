package com.chaturbate.dvr.service;

import com.chaturbate.dvr.utils.HlsParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * HLS 直播录制器（完整修复版 - 支持音视频分离）
 * 
 * 核心思路：
 * 1. 解析 master.m3u8 → 获取视频流 + 音频流
 * 2. 解析 video chunklist → 下载 init 片段 + 视频分片
 * 3. 解析 audio chunklist → 下载 init 片段 + 音频分片
 * 4. 使用 ffmpeg 合并音视频为 MP4
 */
@Slf4j
@Service
public class HlsRecorder {

    @Autowired
    private SystemConfigService configService;

    // 配置项（从数据库加载，带默认值）
    private String getRecordPath() {
        return configService.getConfigValue("record_path", "./recordings");
    }

    private String getTmpPath() {
        return configService.getConfigValue("tmp_path", "./tmp");
    }

    private String getCookie() {
        return configService.getConfigValue("cookie", "");
    }

    private String getUserAgent() {
        return configService.getConfigValue("user_agent", 
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
    }

    private String getPreferredQuality() {
        return configService.getConfigValue("preferred_quality", "720p");
    }

    private String getFfmpegPath() {
        return configService.getConfigValue("ffmpeg_path", "ffmpeg");
    }

    private int getDownloadThreads() {
        return configService.getConfigValueAsInt("download_threads", 4);
    }

    private int getMergeSegments() {
        return configService.getConfigValueAsInt("merge_segments", 10);
    }

    private String getApiBaseUrl() {
        return configService.getConfigValue("api_base_url", 
            "https://zh-hans.chaturbate.com/api/chatvideocontext/");
    }

    private int getSegmentDurationSeconds() {
        return configService.getConfigValueAsInt("segment_duration_seconds", 10);
    }

    private int getCheckIntervalSeconds() {
        return configService.getConfigValueAsInt("check_interval_seconds", 30);
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, RecordingTask> activeRecordings = new ConcurrentHashMap<>();

    /**
     * 开始录制
     */
    public String startRecording(String username, String masterM3u8Url) {
        if (activeRecordings.containsKey(username)) {
            log.warn("直播间 [{}] 已经在录制中", username);
            return activeRecordings.get(username).getTaskId();
        }

        String taskId = username + "_" + System.currentTimeMillis();
        RecordingTask task = new RecordingTask(taskId, username, masterM3u8Url);
        activeRecordings.put(username, task);

        executor.submit(() -> {
            try {
                doRecording(task);
            } catch (Exception e) {
                log.error("录制任务 [{}] 异常: {}", taskId, e.getMessage(), e);
                task.setError(e.getMessage());
            } finally {
                task.cleanup();
                activeRecordings.remove(username);
            }
        });

        log.info("开始录制直播间 [{}], 任务ID: {}", username, taskId);
        return taskId;
    }

    /**
     * 停止录制
     */
    public void stopRecording(String username) {
        RecordingTask task = activeRecordings.get(username);
        if (task != null) {
            task.stop();
            log.info("停止录制直播间 [{}]", username);
        }
    }

    /**
     * 是否正在录制
     */
    public boolean isRecording(String username) {
        return activeRecordings.containsKey(username);
    }

    /**
     * 获取录制任务
     */
    public RecordingTask getRecordingTask(String username) {
        return activeRecordings.get(username);
    }

    /**
     * 执行录制（支持音视频分离）
     */
    private void doRecording(RecordingTask task) throws Exception {
        // 1. 解析 master.m3u8，选择视频流 + 音频流
        HlsParser.MasterPlaylistInfo playlistInfo = selectStreams(task.getMasterM3u8Url(), getPreferredQuality());
        if (playlistInfo == null) {
            throw new RuntimeException("无法解析 master.m3u8");
        }

        String videoChunklistUrl = playlistInfo.getSelectedVideoChunklist();
        String audioChunklistUrl = playlistInfo.getSelectedAudioChunklist();
        
        task.setChunklistUrl(videoChunklistUrl);
        log.info("选择视频流: {}", videoChunklistUrl);
        if (audioChunklistUrl != null) {
            log.info("选择音频流: {}", audioChunklistUrl);
        }

        // 2. 创建临时目录
        Path tmpDir = Paths.get(getTmpPath(), task.getTaskId());
        Files.createDirectories(tmpDir);
        task.setTmpDir(tmpDir);

        // 3. 创建输出文件目录
        Path outputDir = Paths.get(getRecordPath(), task.getUsername());
        Files.createDirectories(outputDir);
        task.setOutputDir(outputDir);

        // 4. 启动下载和合并循环（同时下载音视频）
        List<String> videoFiles = new ArrayList<>();
        List<String> audioFiles = new ArrayList<>();
        
        downloadAndMergeLoop(task, videoChunklistUrl, audioChunklistUrl, tmpDir, videoFiles, audioFiles);

        // 5. 录制结束后，合并所有 part 文件
        if (task.getPartCount() > 0) {
            mergeAllParts(task);
            log.info("录制 [{}] 完成, 文件: {}, 大小: {} bytes",
                    task.getUsername(), task.getFinalOutputFile(),
                    Files.exists(task.getFinalOutputFile()) ? Files.size(task.getFinalOutputFile()) : 0);
        } else {
            log.warn("录制 [{}] 完成, 但没有生成任何 part 文件（可能未下载到任何片段）", task.getUsername());
        }
    }

    /**
     * 解析 master.m3u8，选择视频流和对应的音频流
     */
    private HlsParser.MasterPlaylistInfo selectStreams(String masterUrl, String preferredQuality) throws Exception {
        String content = httpGet(masterUrl);
        HlsParser.MasterPlaylistInfo playlistInfo = HlsParser.parseMasterPlaylist(content);
        
        List<HlsParser.StreamInfo> videoStreams = playlistInfo.getVideoStreams();
        if (videoStreams.isEmpty()) {
            throw new RuntimeException("master.m3u8 中没有找到可用的视频流");
        }

        // 解析期望分辨率高度
        int targetHeight = parseQualityHeight(preferredQuality);

        // 按分辨率高度排序（从高到低）
        videoStreams.sort((a, b) -> Integer.compare(b.getResolutionHeight(), a.getResolutionHeight()));

        // 匹配：优先选择 >= 目标分辨率的流
        HlsParser.StreamInfo selectedVideo = videoStreams.get(0); // 默认最高分辨率
        for (HlsParser.StreamInfo stream : videoStreams) {
            if (stream.getResolutionHeight() >= targetHeight) {
                selectedVideo = stream;
                break;
            }
        }

        log.info("选择分辨率: {} (带宽: {})", selectedVideo.getResolution(), selectedVideo.getBandwidth());
        
        // 转换视频 chunklist 为绝对 URL
        String videoChunklistUrl = toAbsoluteUrl(masterUrl, selectedVideo.getChunklistUrl());
        log.info("视频 chunklist: {} -> {}", selectedVideo.getChunklistUrl(), videoChunklistUrl);
        
        // 查找对应的音频流
        String audioGroupId = selectedVideo.getAudioGroupId();
        String audioChunklistUrl = null;
        if (audioGroupId != null) {
            HlsParser.AudioStreamInfo audioStream = playlistInfo.getAudioStreamByGroupId(audioGroupId);
            if (audioStream != null) {
                audioChunklistUrl = toAbsoluteUrl(masterUrl, audioStream.getChunklistUrl());
                log.info("音频 chunklist: {} -> {}", audioStream.getChunklistUrl(), audioChunklistUrl);
            }
        }
        
        playlistInfo.setSelectedVideoChunklist(videoChunklistUrl);
        playlistInfo.setSelectedAudioChunklist(audioChunklistUrl);
        
        return playlistInfo;
    }

    /**
     * 解析质量字符串为分辨率高度
     */
    private int parseQualityHeight(String quality) {
        if (quality == null) return 720;
        String lower = quality.toLowerCase();
        if (lower.contains("1080")) return 1080;
        if (lower.contains("720")) return 720;
        if (lower.contains("480")) return 480;
        if (lower.contains("360")) return 360;
        try {
            return Integer.parseInt(lower.replace("p", ""));
        } catch (NumberFormatException e) {
            return 720;
        }
    }

    /**
     * 下载并合并循环（同时处理音视频）
     */
    private void downloadAndMergeLoop(RecordingTask task, String videoChunklistUrl, String audioChunklistUrl, 
            Path tmpDir, List<String> videoFiles, List<String> audioFiles) throws Exception {
        
        int lastVideoSegmentCount = 0;
        int lastAudioSegmentCount = 0;
        int emptyChunklistCount = 0;
        
        // 用于跟踪已下载的片段URL，避免重复下载
        Set<String> downloadedVideoUrls = new HashSet<>();
        Set<String> downloadedAudioUrls = new HashSet<>();

        // 下载 init 片段（如果存在）
        downloadInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);

        while (!task.isStopped()) {
            try {
                long loopStartTime = System.currentTimeMillis();
                
                // 1. 同时获取视频和音频 chunklist
                String videoChunklistContent = httpGet(videoChunklistUrl);
                Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent);
                @SuppressWarnings("unchecked")
                List<HlsParser.SegmentInfo> videoSegments = (List<HlsParser.SegmentInfo>) videoParseResult.get("segments");

                List<HlsParser.SegmentInfo> audioSegments = new ArrayList<>();
                if (audioChunklistUrl != null) {
                    String audioChunklistContent = httpGet(audioChunklistUrl);
                    Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent);
                    audioSegments = (List<HlsParser.SegmentInfo>) audioParseResult.get("segments");
                }

                log.debug("视频 chunklist: {} 个片段, 音频 chunklist: {} 个片段", 
                        videoSegments.size(), audioSegments.size());

                // 2. 检查是否为空
                if (videoSegments.isEmpty()) {
                    emptyChunklistCount++;
                    log.warn("视频 chunklist 为空 (第 {} 次)", emptyChunklistCount);
                    if (emptyChunklistCount >= 3) {
                        log.info("连续 {} 次 chunklist 为空，停止录制", emptyChunklistCount);
                        break;
                    }
                } else {
                    emptyChunklistCount = 0;
                }

                // 3. 找出新片段（基于URL去重，而不是基于索引）
                List<HlsParser.SegmentInfo> newVideoSegments = new ArrayList<>();
                for (HlsParser.SegmentInfo segment : videoSegments) {
                    String absoluteUrl = toAbsoluteUrl(videoChunklistUrl, segment.getUrl());
                    if (!downloadedVideoUrls.contains(absoluteUrl)) {
                        newVideoSegments.add(segment);
                        downloadedVideoUrls.add(absoluteUrl);
                    }
                }

                List<HlsParser.SegmentInfo> newAudioSegments = new ArrayList<>();
                for (HlsParser.SegmentInfo segment : audioSegments) {
                    String absoluteUrl = toAbsoluteUrl(audioChunklistUrl, segment.getUrl());
                    if (!downloadedAudioUrls.contains(absoluteUrl)) {
                        newAudioSegments.add(segment);
                        downloadedAudioUrls.add(absoluteUrl);
                    }
                }

                // 4. 同时下载视频和音频片段（使用同一个线程池）
                if (!newVideoSegments.isEmpty() || !newAudioSegments.isEmpty()) {
                    log.info("发现 {} 个新视频片段, {} 个新音频片段，开始并行下载...", 
                            newVideoSegments.size(), newAudioSegments.size());
                    
                    List<String> newVideoFiles = new ArrayList<>();
                    List<String> newAudioFiles = new ArrayList<>();
                    
                    downloadSegmentsParallel(task, newVideoSegments, newAudioSegments, 
                            videoChunklistUrl, audioChunklistUrl, tmpDir, 
                            newVideoFiles, newAudioFiles);
                    
                    videoFiles.addAll(newVideoFiles);
                    audioFiles.addAll(newAudioFiles);
                    
                    log.info("已下载 {} 个视频片段, {} 个音频片段", 
                            newVideoFiles.size(), newAudioFiles.size());
                }

                // 5. 每 N 个片段触发一次合并
                int totalSegments = videoFiles.size() + audioFiles.size();
                if (totalSegments >= (task.getPartCount() + 1) * getMergeSegments()) {
                    log.info("达到合并阈值: {} >= {}, 开始合并 part {}",
                            totalSegments, (task.getPartCount() + 1) * getMergeSegments(), task.getPartCount() + 1);
                    mergeToPartFile(task, videoFiles, audioFiles, task.getPartCount());
                }

                // 控制循环频率，但不要等待太久
                long elapsed = System.currentTimeMillis() - loopStartTime;
                long sleepTime = Math.max(500, 2000 - elapsed); // 至少等500ms，最多等2秒
                Thread.sleep(sleepTime);

            } catch (Exception e) {
                log.error("下载循环异常: {}", e.getMessage(), e);
                if (!task.isStopped()) {
                    Thread.sleep(2000);
                }
            }
        }

        // 6. 录制结束后，合并剩余的片段
        int mergedCount = task.getPartCount() * getMergeSegments();
        if (videoFiles.size() + audioFiles.size() > mergedCount) {
            log.info("合并剩余片段: {} 个 (已合并: {}, 总计: {})",
                    (videoFiles.size() + audioFiles.size()) - mergedCount, mergedCount, videoFiles.size() + audioFiles.size());
            mergeToPartFile(task, videoFiles, audioFiles, task.getPartCount());
        } else if (videoFiles.isEmpty() && audioFiles.isEmpty()) {
            log.warn("没有下载到任何片段，可能的原因：\n" +
                    "  1. Cookie 已过期\n" +
                    "  2. chunklist URL 无效\n" +
                    "  3. 网络连接有问题");
        }
    }

    /**
     * 下载 init 片段（视频 + 音频）
     */
    private void downloadInitSegments(RecordingTask task, String videoChunklistUrl, String audioChunklistUrl, Path tmpDir) {
        try {
            // 下载视频 init 片段
            String videoChunklistContent = httpGet(videoChunklistUrl);
            Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent);
            String videoInitUrl = (String) videoParseResult.get("initSegmentUrl");
            
            if (videoInitUrl != null) {
                String initUrl = toAbsoluteUrl(videoChunklistUrl, videoInitUrl);
                String filename = downloadSegment(initUrl, tmpDir);  // ✅ 修复：捕获返回值
                Path initFile = tmpDir.resolve(filename);
                task.setInitVideoSegmentFile(initFile);
                log.info("下载视频 init 片段: {} -> {}", videoInitUrl, initFile);
            }

            // 下载音频 init 片段
            if (audioChunklistUrl != null) {
                String audioChunklistContent = httpGet(audioChunklistUrl);
                Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent);
                String audioInitUrl = (String) audioParseResult.get("initSegmentUrl");
                
                if (audioInitUrl != null) {
                    String initUrl = toAbsoluteUrl(audioChunklistUrl, audioInitUrl);
                    String filename = downloadSegment(initUrl, tmpDir);  // ✅ 修复：捕获返回值
                    Path initFile = tmpDir.resolve(filename);
                    task.setInitAudioSegmentFile(initFile);
                    log.info("下载音频 init 片段: {} -> {}", audioInitUrl, initFile);
                }
            }
        } catch (Exception e) {
            log.error("下载 init 片段失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 并行下载视频和音频片段
     * 使用同一个线程池同时下载，确保链接在有效期内完成
     */
    private void downloadSegmentsParallel(RecordingTask task, 
            List<HlsParser.SegmentInfo> videoSegments, List<HlsParser.SegmentInfo> audioSegments,
            String videoChunklistUrl, String audioChunklistUrl, Path tmpDir,
            List<String> videoFiles, List<String> audioFiles) {
        
        int totalSegments = videoSegments.size() + audioSegments.size();
        ExecutorService downloadPool = Executors.newFixedThreadPool(Math.min(totalSegments, getDownloadThreads() * 2));
        List<Future<DownloadResult>> futures = new ArrayList<>();

        // 提交视频下载任务
        for (HlsParser.SegmentInfo segment : videoSegments) {
            futures.add(downloadPool.submit(() -> {
                try {
                    String absoluteUrl = toAbsoluteUrl(videoChunklistUrl, segment.getUrl());
                    String filename = downloadSegment(absoluteUrl, tmpDir);
                    return new DownloadResult(filename, "video");
                } catch (Exception e) {
                    log.error("下载视频片段失败 [{}]: {}", segment.getUrl(), e.getMessage());
                    return null;
                }
            }));
        }

        // 提交音频下载任务
        for (HlsParser.SegmentInfo segment : audioSegments) {
            futures.add(downloadPool.submit(() -> {
                try {
                    String absoluteUrl = toAbsoluteUrl(audioChunklistUrl, segment.getUrl());
                    String filename = downloadSegment(absoluteUrl, tmpDir);
                    return new DownloadResult(filename, "audio");
                } catch (Exception e) {
                    log.error("下载音频片段失败 [{}]: {}", segment.getUrl(), e.getMessage());
                    return null;
                }
            }));
        }

        downloadPool.shutdown();

        // 收集结果
        for (Future<DownloadResult> future : futures) {
            try {
                DownloadResult result = future.get(30, TimeUnit.SECONDS);
                if (result != null && result.filename != null) {
                    if ("video".equals(result.type)) {
                        videoFiles.add(result.filename);
                    } else {
                        audioFiles.add(result.filename);
                    }
                }
            } catch (Exception e) {
                log.error("等待下载完成时发生异常: {}", e.getMessage());
            }
        }

        // 按文件名排序
        videoFiles.sort(String::compareTo);
        audioFiles.sort(String::compareTo);
    }

    /**
     * 下载结果
     */
    private static class DownloadResult {
        final String filename;
        final String type;
        
        DownloadResult(String filename, String type) {
            this.filename = filename;
            this.type = type;
        }
    }

    /**
     * 下载单个片段
     */
    private String downloadSegment(String segmentUrl, Path tmpDir) throws Exception {
        String filename = segmentUrl.substring(segmentUrl.lastIndexOf("/") + 1);
        Path outputPath = tmpDir.resolve(filename);

        if (Files.exists(outputPath)) {
            log.debug("片段已存在，跳过: {}", filename);
            return filename;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(segmentUrl).openConnection();
        conn.setRequestProperty("Cookie", getCookie());
        conn.setRequestProperty("User-Agent", getUserAgent());
        conn.setRequestProperty("Referer", "https://zh-hans.chaturbate.com/");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        log.debug("下载完成: {}", filename);
        return filename;
    }

    /**
     * 合并片段到 part 文件（支持音视频）
     * 
     * 新方案：每个分片保存为独立文件，使用 ffmpeg concat demuxer 合并
     * 1. 每个分片保存为 audio_1.mp4, video_1.mp4 等独立文件
     * 2. 生成 concat.txt 列出所有文件
     * 3. ffmpeg 合并为最终 MP4
     */
    private void mergeToPartFile(RecordingTask task, List<String> videoFiles, List<String> audioFiles, int partIndex) throws Exception {
        // 1. 计算本次合并的片段范围
        int startIndex = partIndex * getMergeSegments();
        int endIndex = Math.min(startIndex + getMergeSegments(), videoFiles.size());

        if (startIndex >= endIndex) {
            return;
        }

        // 2. 生成 concat.txt
        Path concatFile = task.getTmpDir().resolve("concat_part" + (partIndex + 1) + ".txt");
        try (BufferedWriter writer = Files.newBufferedWriter(concatFile)) {
            // 视频片段
            for (int i = startIndex; i < endIndex; i++) {
                String filename = videoFiles.get(i);
                Path filePath = task.getTmpDir().resolve(filename);
                if (Files.exists(filePath)) {
                    writer.write("file '" + filePath.toAbsolutePath().toString() + "'");
                    writer.newLine();
                }
            }
        }

        // 3. 检查是否有音频
        Path partFile = task.getOutputDir().resolve("part" + (partIndex + 1) + ".mp4");
        
        int audioEndIndex = Math.min(startIndex + getMergeSegments(), audioFiles.size());
        boolean hasAudio = !audioFiles.isEmpty() && audioFiles.size() > startIndex;
        
        if (!hasAudio) {
            // 无音频：直接用 concat demuxer 合并视频片段
            String[] command = {
                    getFfmpegPath(),
                    "-loglevel", "info",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile.toAbsolutePath().toString(),
                    "-c", "copy",
                    partFile.toAbsolutePath().toString()
            };
            log.info("合并视频 part {}: {} 个片段 -> {}", partIndex + 1, (endIndex - startIndex), partFile);
            runFfmpeg(command, task);
        } else {
            // 有音频：分别合并视频和音频，然后混合
            Path videoPartFile = task.getTmpDir().resolve("video_part" + (partIndex + 1) + ".mp4");
            Path audioPartFile = task.getTmpDir().resolve("audio_part" + (partIndex + 1) + ".mp4");
            
            // 合并视频
            String[] videoCommand = {
                    getFfmpegPath(),
                    "-loglevel", "info",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile.toAbsolutePath().toString(),
                    "-c", "copy",
                    videoPartFile.toAbsolutePath().toString()
            };
            runFfmpeg(videoCommand, task);
            
            // 生成音频 concat.txt
            Path audioConcatFile = task.getTmpDir().resolve("concat_audio_part" + (partIndex + 1) + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(audioConcatFile)) {
                for (int i = startIndex; i < audioEndIndex; i++) {
                    String filename = audioFiles.get(i);
                    Path filePath = task.getTmpDir().resolve(filename);
                    if (Files.exists(filePath)) {
                        writer.write("file '" + filePath.toAbsolutePath().toString() + "'");
                        writer.newLine();
                    }
                }
            }
            
            // 合并音频
            String[] audioCommand = {
                    getFfmpegPath(),
                    "-loglevel", "info",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", audioConcatFile.toAbsolutePath().toString(),
                    "-c", "copy",
                    audioPartFile.toAbsolutePath().toString()
            };
            runFfmpeg(audioCommand, task);
            
            // 混合音视频
            String[] mergeCommand = {
                    getFfmpegPath(),
                    "-loglevel", "info",
                    "-y",
                    "-i", videoPartFile.toAbsolutePath().toString(),
                    "-i", audioPartFile.toAbsolutePath().toString(),
                    "-c", "copy",
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    partFile.toAbsolutePath().toString()
            };
            log.info("混合音视频 part {}: {} + {} -> {}", 
                    partIndex + 1, videoPartFile.getFileName(), audioPartFile.getFileName(), partFile);
            runFfmpeg(mergeCommand, task);
            
            // 删除临时文件
            Files.deleteIfExists(videoPartFile);
            Files.deleteIfExists(audioPartFile);
            Files.deleteIfExists(audioConcatFile);
        }
        
        // 删除 concat 文件
        Files.deleteIfExists(concatFile);

        // 4. 删除已合并的片段文件，并从列表中移除
        for (int i = startIndex; i < endIndex; i++) {
            String filename = videoFiles.get(startIndex);
            Path filePath = task.getTmpDir().resolve(filename);
            try {
                Files.deleteIfExists(filePath);
                log.debug("删除已合并视频片段: {}", filename);
            } catch (IOException e) {
                log.warn("删除视频片段失败 [{}]: {}", filename, e.getMessage());
            }
            videoFiles.remove(startIndex);
        }

        for (int i = startIndex; i < audioEndIndex; i++) {
            String filename = audioFiles.get(startIndex);
            Path filePath = task.getTmpDir().resolve(filename);
            try {
                Files.deleteIfExists(filePath);
                log.debug("删除已合并音频片段: {}", filename);
            } catch (IOException e) {
                log.warn("删除音频片段失败 [{}]: {}", filename, e.getMessage());
            }
            audioFiles.remove(startIndex);
        }

        // 5. 更新 task 的 part 计数
        task.incrementPartCount();

        log.info("Part {} 合并完成, 大小: {} bytes",
                partIndex + 1,
                Files.exists(partFile) ? Files.size(partFile) : 0);
    }

    /**
     * 合并所有 part 文件为最终输出文件
     * 使用 ffmpeg concat protocol 合并多个独立 MP4 文件
     */
    private void mergeAllParts(RecordingTask task) throws Exception {
        if (task.getPartCount() == 0) {
            log.warn("没有 part 文件需要合并");
            return;
        }

        // 1. 收集所有存在的 part 文件
        List<Path> partFiles = new ArrayList<>();
        for (int i = 0; i < task.getPartCount(); i++) {
            Path partFile = task.getOutputDir().resolve("part" + (i + 1) + ".mp4");
            if (Files.exists(partFile)) {
                partFiles.add(partFile);
            }
        }

        if (partFiles.isEmpty()) {
            log.warn("没有有效的 part 文件需要合并");
            return;
        }

        // 2. 生成最终输出文件名
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("%s_%s.mp4", task.getUsername(), dateStr);
        Path finalOutput = task.getOutputDir().resolve(filename);
        task.setFinalOutputFile(finalOutput);

        // 3. 使用 ffmpeg concat protocol 合并
        // 格式: ffmpeg -i "concat:file1|file2|file3" -c copy output.mp4
        StringBuilder concatInput = new StringBuilder("concat:");
        for (int i = 0; i < partFiles.size(); i++) {
            if (i > 0) {
                concatInput.append("|");
            }
            concatInput.append(partFiles.get(i).toAbsolutePath().toString());
        }

        String[] command = {
                getFfmpegPath(),
                "-loglevel", "info",
                "-y",
                "-i", concatInput.toString(),
                "-c", "copy",
                finalOutput.toAbsolutePath().toString()
        };

        log.info("合并所有 part 文件: {} parts -> {}", partFiles.size(), finalOutput);
        runFfmpeg(command, task);

        // 4. 删除 part 文件
        for (Path partFile : partFiles) {
            try {
                Files.deleteIfExists(partFile);
                log.debug("删除 part 文件: {}", partFile.getFileName());
            } catch (IOException e) {
                log.warn("删除 part 文件失败 [{}]: {}", partFile.getFileName(), e.getMessage());
            }
        }

        log.info("最终合并完成: {}", finalOutput);
    }

    /**
     * 运行 ffmpeg 命令
     */
    private void runFfmpeg(String[] command, RecordingTask task) throws Exception {
        log.info("执行 ffmpeg: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 读取日志
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                task.addLog(line);
                if (log.isDebugEnabled()) {
                    if (line.contains("frame=") || line.contains("time=")) {
                        log.debug("[ffmpeg] {}", line);
                    }
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("ffmpeg 退出码: " + exitCode);
        }
    }

    /**
     * HTTP GET 请求
     */
    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("Cookie", getCookie());
        conn.setRequestProperty("User-Agent", getUserAgent());
        conn.setRequestProperty("Referer", "https://zh-hans.chaturbate.com/");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 将相对路径转换为绝对 URL
     */
    private String toAbsoluteUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl.startsWith("http")) {
            return relativeUrl;
        }

        if (relativeUrl.startsWith("/")) {
            // 相对路径：需要拼接 baseUrl 的基础部分
            int protoIdx = baseUrl.indexOf("://");
            if (protoIdx > 0) {
                String base = baseUrl.substring(0, baseUrl.indexOf("/", protoIdx + 3));
                return base + relativeUrl;
            }
        } else {
            // 相对路径（相对于 baseUrl 的目录）
            String base = baseUrl.substring(0, baseUrl.lastIndexOf("/") + 1);
            return base + relativeUrl;
        }

        return relativeUrl;
    }

    /**
     * 录制任务内部类
     */
    public class RecordingTask {
        private final String taskId;
        private final String username;
        private final String masterM3u8Url;
        private String chunklistUrl;
        private Path tmpDir;
        private Path outputDir;
        private Path finalOutputFile;
        private Path initVideoSegmentFile;
        private Path initAudioSegmentFile;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private int partCount = 0;

        public RecordingTask(String taskId, String username, String masterM3u8Url) {
            this.taskId = taskId;
            this.username = username;
            this.masterM3u8Url = masterM3u8Url;
        }

        public String getTaskId() { return taskId; }
        public String getUsername() { return username; }
        public String getMasterM3u8Url() { return masterM3u8Url; }
        public String getChunklistUrl() { return chunklistUrl; }
        public void setChunklistUrl(String chunklistUrl) { this.chunklistUrl = chunklistUrl; }
        public Path getTmpDir() { return tmpDir; }
        public void setTmpDir(Path tmpDir) { this.tmpDir = tmpDir; }
        public Path getOutputDir() { return outputDir; }
        public void setOutputDir(Path outputDir) { this.outputDir = outputDir; }
        public Path getFinalOutputFile() { return finalOutputFile; }
        public void setFinalOutputFile(Path finalOutputFile) { this.finalOutputFile = finalOutputFile; }
        
        public Path getInitVideoSegmentFile() { return initVideoSegmentFile; }
        public void setInitVideoSegmentFile(Path initVideoSegmentFile) { this.initVideoSegmentFile = initVideoSegmentFile; }
        public Path getInitAudioSegmentFile() { return initAudioSegmentFile; }
        public void setInitAudioSegmentFile(Path initAudioSegmentFile) { this.initAudioSegmentFile = initAudioSegmentFile; }

        public boolean isStopped() { return stopped.get(); }
        public void stop() { stopped.set(true); }

        public void addLog(String logLine) {
            synchronized (logs) {
                if (logs.size() < 1000) {
                    logs.add(logLine);
                }
            }
        }

        public List<String> getLogs() {
            synchronized (logs) {
                return new ArrayList<>(logs);
            }
        }

        public int getPartCount() { return partCount; }
        public void incrementPartCount() { partCount++; }

        public void cleanup() {
            try {
                if (tmpDir != null && Files.exists(tmpDir)) {
                    Files.walk(tmpDir)
                         .sorted(Comparator.reverseOrder())
                         .forEach(path -> {
                             try { Files.deleteIfExists(path); } catch (IOException e) { /* ignore */ }
                         });
                    log.info("清理临时目录: {}", tmpDir);
                }
            } catch (Exception e) {
                log.error("清理临时目录失败: {}", e.getMessage());
            }
        }

        public void setError(String error) {
            addLog("ERROR: " + error);
        }
    }
}
