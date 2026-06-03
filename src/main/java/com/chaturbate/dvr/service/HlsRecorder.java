package com.chaturbate.dvr.service;

import com.chaturbate.dvr.dto.ChatVideoContext;
import com.chaturbate.dvr.dto.DownloadResult;
import com.chaturbate.dvr.exception.UrlExpiredException;
import com.chaturbate.dvr.task.RecordingTask;
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
import java.util.stream.Collectors;

/**
 * HLS 直播录制器
 * <p>
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

    @Autowired
    private ChaturbateApiService apiService;

    // 配置项（从数据库加载，带默认值）
    private String getRecordPath() {
        return configService.getConfigValue("record_path", "./recordings");
    }

    /**
     * 解析录制路径中的占位符
     * 支持：
     * {username}     - 主播用户名
     * {yyyy-mm-dd}  - 当前日期
     * 示例：./recordings/{username}/{yyyy-mm-dd} -> ./recordings/streamer_name/2026-06-02
     */
    private String resolveRecordPath(String pathTemplate, String username) {
        if (pathTemplate == null || pathTemplate.isEmpty()) {
            return "./recordings";
        }
        String result = pathTemplate;
        if (username != null) {
            result = result.replace("{username}", username);
        }
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        result = result.replace("{yyyy-mm-dd}", dateStr);
        return result;
    }

    private String getTmpPath() {
        return configService.getConfigValue("tmp_path", "./tmp");
    }

    private String getPreferredQuality() {
        return configService.getConfigValue("preferred_quality", "720p");
    }

    private String getFfmpegPath() {
        return configService.getConfigValue("ffmpeg_path", "ffmpeg");
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, RecordingTask> activeRecordings = new ConcurrentHashMap<>();

    // -------------------- 公开 API --------------------

    /**
     * 开始录制
     */
    public String startRecording(String username, String masterM3u8Url) {
        if (activeRecordings.containsKey(username)) {
            log.warn("直播间 [{}] 已经在录制中", username);
            return activeRecordings.get(username).getTaskId();
        }

        String taskId = username + "_" + System.currentTimeMillis();
        RecordingTask task = new RecordingTask(taskId, username, masterM3u8Url, getTmpPath());
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
            // 立即从 activeRecordings 移除，避免录制线程还未退出时列表仍显示
            activeRecordings.remove(username);
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
     * 获取正在录制的用户名列表
     */
    public List<String> getRecordingUsernames() {
        return new ArrayList<>(activeRecordings.keySet());
    }

    /**
     * 获取下载信息
     */
    public Map<String, Object> getDownloadInfo(String username) {
        Map<String, Object> info = new HashMap<>();
        RecordingTask task = activeRecordings.get(username);
        if (task == null) {
            info.put("active", false);
            return info;
        }
        info.put("active", true);
        info.put("username", username);
        info.put("downloadUrl", task.getMasterM3u8Url());
        info.put("runtimeSeconds", task.getRuntimeSeconds());
        info.put("partCount", task.getPartCount());
        info.put("format", task.getFormat());
        info.put("isStopped", task.isStopped());

        // 添加活跃下载列表
        List<Map<String, Object>> activeList = new ArrayList<>();
        for (var download : task.getActiveDownloads()) {
            Map<String, Object> d = new HashMap<>();
            d.put("url", download.getUrl());
            d.put("type", download.getType());
            d.put("filename", download.getFilename());
            d.put("elapsedSeconds", download.getElapsedSeconds());
            d.put("startTime", download.getStartTime());
            activeList.add(d);
        }
        info.put("activeDownloads", activeList);
        info.put("activeDownloadCount", task.getActiveDownloadCount());

        return info;
    }

    // -------------------- 录制主流程 --------------------

    /**
     * 执行录制（支持音视频分离）
     * 当遇到 403 时自动刷新 m3u8 地址并重试
     */
    private void doRecording(RecordingTask task) throws Exception {
        String[] currentVideoUrl = {null};
        String[] currentAudioUrl = {null};

        while (!task.isStopped()) {
            try {
                // 1. 首次解析 master.m3u8，后续通过 UrlExpiredException 刷新
                HlsParser.MasterPlaylistInfo playlistInfo = selectStreams(task.getMasterM3u8Url(), getPreferredQuality());
                if (playlistInfo.getFormat() == null) {
                    throw new RuntimeException("无法解析 master.m3u8");
                }
                currentVideoUrl[0] = playlistInfo.getSelectedVideoChunklist();
                currentAudioUrl[0] = playlistInfo.getSelectedAudioChunklist();
                task.setFormat(playlistInfo.getFormat());

                task.setChunklistUrl(currentVideoUrl[0]);
                log.info("录制视频流: {}", currentVideoUrl[0]);
                if (currentAudioUrl[0] != null) {
                    log.info("录制音频流: {}", currentAudioUrl[0]);
                }

                // 2. 创建临时目录
                Path tmpDir = Paths.get(getTmpPath(), task.getTaskId());
                Files.createDirectories(tmpDir);
                task.setTmpDir(tmpDir);

                // 3. 解析录制路径（支持占位符：{username}, {yyyy-mm-dd}）
                String resolvedFilePath = resolveRecordPath(getRecordPath(), task.getUsername());
                Path finalOutputFile = Paths.get(resolvedFilePath);
                Path outputDir = finalOutputFile.getParent();
                Files.createDirectories(outputDir);
                task.setOutputDir(outputDir);
                task.setFinalOutputFile(finalOutputFile);

                // 4. 启动下载和合并循环
                if ("ts".equals(task.getFormat())) {
                    downloadAndMergeLoopTs(task, currentVideoUrl[0], currentAudioUrl[0], tmpDir);
                } else {
                    downloadAndMergeLoopM4s(task, currentVideoUrl[0], currentAudioUrl[0], tmpDir);
                }

                // 5. 录制结束，合并所有 part 文件（仅 fMP4 需要，TS 已在循环中合并）
                if ("fmp4".equals(task.getFormat()) && task.getPartCount() > 0) {
                    mergeAllParts(task);
                    log.info("录制 [{}] 完成, 文件: {}, 大小: {} bytes",
                            task.getUsername(), task.getFinalOutputFile(),
                            Files.exists(task.getFinalOutputFile()) ? Files.size(task.getFinalOutputFile()) : 0);
                } else if ("ts".equals(task.getFormat())) {
                    log.info("录制 [{}] 完成 (TS 格式实时合并)", task.getUsername());
                } else {
                    log.warn("录制 [{}] 完成, 但没有生成任何文件", task.getUsername());
                }
                break;
            } catch (UrlExpiredException e) {
                log.warn("检测到 URL 过期 (403)，准备刷新... ");
                ChatVideoContext context = apiService.getChatVideoContext(task.getUsername());
                if (context != null && context.isPublicLive()) {
                    task.setMasterM3u8Url(context.getHlsSource());
                    currentVideoUrl[0] = null;
                    currentAudioUrl[0] = null;
                    log.warn("检测到 URL 过期 (403)，已刷新最新url:{}, 继续录制", task.getMasterM3u8Url());
                    continue;
                } else if (context == null) {
                    log.error("获取直播间信息失败");
                    throw new RuntimeException("获取直播间信息失败");
                } else {
                    log.error("直播间已结束");
                    throw new RuntimeException("直播间已结束");
                }
            }
        }
    }

    /**
     * 解析 master.m3u8，选择视频流和对应的音频流
     */
    private HlsParser.MasterPlaylistInfo selectStreams(String masterUrl, String preferredQuality) {
        String content = httpGet(masterUrl);
        HlsParser.MasterPlaylistInfo playlistInfo = HlsParser.parseMasterPlaylist(content);

        List<HlsParser.StreamInfo> videoStreams = playlistInfo.getVideoStreams();
        if (videoStreams.isEmpty()) {
            throw new RuntimeException("master.m3u8 中没有找到可用的视频流");
        }

        int targetHeight = parseQualityHeight(preferredQuality);
        videoStreams.sort((a, b) -> Integer.compare(b.getResolutionHeight(), a.getResolutionHeight()));

        HlsParser.StreamInfo selectedVideo = videoStreams.getFirst();
        for (HlsParser.StreamInfo stream : videoStreams) {
            if (stream.getResolutionHeight() >= targetHeight) {
                selectedVideo = stream;
                break;
            }
        }

        log.info("选择分辨率: {} (带宽: {})", selectedVideo.getResolution(), selectedVideo.getBandwidth());

        String videoChunkListUrl = toAbsoluteUrl(masterUrl, selectedVideo.getChunklistUrl());
        log.info("视频 chunklist: {} -> {}", selectedVideo.getChunklistUrl(), videoChunkListUrl);

        // 检测格式（fMP4 或 TS）
        String testChunkList = httpGet(videoChunkListUrl);
        if (HlsParser.isTsFormat(testChunkList)) {
            log.info("检测到 TS 格式（传统 HLS）");
            playlistInfo.setFormat("ts");
        } else {
            log.info("检测到 fMP4 格式（LL-HLS）");
            playlistInfo.setFormat("fmp4");
        }

        // 查找对应的音频流
        String audioGroupId = selectedVideo.getAudioGroupId();
        String audioChunkListUrl = null;
        if (audioGroupId != null) {
            HlsParser.AudioStreamInfo audioStream = playlistInfo.getAudioStreamByGroupId(audioGroupId);
            if (audioStream != null) {
                audioChunkListUrl = toAbsoluteUrl(masterUrl, audioStream.getChunklistUrl());
                log.info("音频 chunklist: {} -> {}", audioStream.getChunklistUrl(), audioChunkListUrl);
            }
        }

        playlistInfo.setSelectedVideoChunklist(videoChunkListUrl);
        playlistInfo.setSelectedAudioChunklist(audioChunkListUrl);

        return playlistInfo;
    }

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

    // -------------------- fMP4 下载合并循环 --------------------

    /**
     * 下载并合并循环（fMP4 / LL-HLS 格式）
     * <p>
     * 核心流程：
     * 1. 每次 chunklist 下载完成后立即合并，生成 partN.mp4
     * 2. 每个 part 实时追加到最终文件
     */
    private void downloadAndMergeLoopM4s(RecordingTask task, String videoChunklistUrl,
                                         String audioChunklistUrl, Path tmpDir) throws Exception {

        Set<String> downloadedVideoUrls = ConcurrentHashMap.newKeySet();
        Set<String> downloadedAudioUrls = ConcurrentHashMap.newKeySet();

        List<String> pendingVideoFiles = Collections.synchronizedList(new ArrayList<>());
        List<String> pendingAudioFiles = Collections.synchronizedList(new ArrayList<>());

        BlockingQueue<DownloadResult> resultQueue = new LinkedBlockingQueue<>();
        long pollIntervalMs = 2000;

        int partCounter = 0;
        Path finalOutput = null;

        ExecutorService downloadExecutor = Executors.newCachedThreadPool();

        // 下载 init 片段
        downloadInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);

        while (!task.isStopped()) {
            try {
                long loopStartTime = System.currentTimeMillis();

                // 1. 获取视频和音频 chunklist
                String videoChunklistContent = httpGet(videoChunklistUrl);
                Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent);

                Long partHoldBack = (Long) videoParseResult.get("partHoldBack");
                if (partHoldBack != null && partHoldBack > 0) {
                    pollIntervalMs = Math.max(500, partHoldBack * 1000 - 500);
                    log.debug("从 chunklist 解析到 PART-HOLD-BACK: {}s, 轮询间隔: {}ms", partHoldBack, pollIntervalMs);
                }

                @SuppressWarnings("unchecked")
                List<HlsParser.SegmentInfo> videoSegments = (List<HlsParser.SegmentInfo>) videoParseResult.get("segments");

                List<HlsParser.SegmentInfo> audioSegments = new ArrayList<>();
                if (audioChunklistUrl != null) {
                    String audioChunklistContent = httpGet(audioChunklistUrl);
                    Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent);
                    audioSegments = (List<HlsParser.SegmentInfo>) audioParseResult.get("segments");
                }

                log.debug("视频 chunklist: {} 个片段, 音频 chunklist: {} 个片段", videoSegments.size(), audioSegments.size());

                // 2. 检查 chunklist 是否为空
                if (videoSegments.isEmpty()) {
                    log.warn("视频 chunklist 为空，直播可能已结束");
                    if (pendingVideoFiles.isEmpty() && pendingAudioFiles.isEmpty()) {
                        break;
                    }
                }

                // 3. 提交异步下载任务
                int newVideoCount = 0;
                for (HlsParser.SegmentInfo segment : videoSegments) {
                    String absoluteUrl = toAbsoluteUrl(videoChunklistUrl, segment.getUrl());
                    if (downloadedVideoUrls.add(absoluteUrl)) {
                        final String url = absoluteUrl;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "video", Paths.get(url).getFileName().toString());
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(filename, "video"));
                                log.debug("异步下载完成: {}", filename);
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("异步下载视频片段失败 [{}]: {}", url, e.getMessage());
                            }
                        });
                        newVideoCount++;
                    }
                }

                int newAudioCount = 0;
                for (HlsParser.SegmentInfo segment : audioSegments) {
                    String absoluteUrl = toAbsoluteUrl(audioChunklistUrl, segment.getUrl());
                    if (downloadedAudioUrls.add(absoluteUrl)) {
                        final String url = absoluteUrl;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "audio", Paths.get(url).getFileName().toString());
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(filename, "audio"));
                                log.debug("异步下载完成: {}", filename);
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("异步下载音频片段失败 [{}]: {}", url, e.getMessage());
                            }
                        });
                        newAudioCount++;
                    }
                }

                if (newVideoCount > 0 || newAudioCount > 0) {
                    log.info("提交异步下载任务: {} 个视频, {} 个音频 (队列中待下载: {})",
                            newVideoCount, newAudioCount, resultQueue.size());
                }

                // 4. 等待本次所有新分片下载完成
                int totalNew = newVideoCount + newAudioCount;
                int downloaded = 0;
                while (downloaded < totalNew) {
                    DownloadResult result = resultQueue.poll(5, TimeUnit.SECONDS);
                    if (result != null) {
                        if ("video".equals(result.type)) {
                            pendingVideoFiles.add(result.filename);
                        } else {
                            pendingAudioFiles.add(result.filename);
                        }
                        downloaded++;
                        log.debug("下载进度: {}/{}", downloaded, totalNew);
                    } else {
                        if (newVideoCount == 0 && newAudioCount == 0) break;
                    }
                }

                pendingVideoFiles.sort(String::compareTo);
                pendingAudioFiles.sort(String::compareTo);

                // 5. 本次分片下载完成后，立即合并
                ensureInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);
                Path videoInit = task.getInitVideoSegmentFile();
                Path audioInit = task.getInitAudioSegmentFile();

                if (videoInit == null || !Files.exists(videoInit)) {
                    log.error("视频 init 文件无效: {}", videoInit);
                    continue;
                }
                if (audioInit == null || !Files.exists(audioInit)) {
                    log.error("音频 init 文件无效: {}", audioInit);
                    continue;
                }

                if (!pendingVideoFiles.isEmpty() && !pendingAudioFiles.isEmpty()) {
                    mergeToPartFile(task, pendingVideoFiles, pendingAudioFiles);
                    Path partFile = task.getOutputDir().resolve("part" + task.getPartCount() + ".mp4");
                    appendToFinalFile(partFile, task);
                    partCounter++;
                }

                // 6. 轮询间隔
                long elapsed = System.currentTimeMillis() - loopStartTime;
                long sleepTime = Math.max(100, pollIntervalMs - elapsed);
                log.debug("本轮耗时: {}ms, 休眠: {}ms (PART-HOLD-BACK: {}ms)", elapsed, sleepTime, pollIntervalMs);
                Thread.sleep(sleepTime);

            } catch (Exception e) {
                downloadExecutor.shutdown();
                try {
                    downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e1) {
                    log.warn("下载线程池关闭被中断");
                }
                throw e;
            }
        }

        // 7. 关闭下载线程池
        downloadExecutor.shutdown();
        try {
            downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("下载线程池关闭被中断");
        }

        // 8. 录制结束（parts 已在生成时实时追加到最终文件）
        if (finalOutput != null && Files.exists(finalOutput)) {
            task.setFinalOutputFile(finalOutput);
            log.info("录制完成，实时合并 {} 个 parts -> {} ({} bytes)",
                    partCounter, finalOutput.getFileName(), Files.size(finalOutput));
        } else if (partCounter > 0) {
            log.warn("录制结束但未生成最终文件");
        } else {
            log.warn("没有生成任何 part 文件，录制可能失败");
        }

        // 9. 清理临时文件
        cleanupTempFiles(task);
    }

    // -------------------- TS 下载合并循环 --------------------

    /**
     * 下载并合并循环（TS 格式 - 传统 HLS）
     * 每个 chunklist 下载完成后立即合并到最终文件
     */
    private void downloadAndMergeLoopTs(RecordingTask task, String videoChunklistUrl,
                                         String audioChunklistUrl, Path tmpDir) throws Exception {

        Set<String> downloadedVideoUrls = ConcurrentHashMap.newKeySet();
        Set<String> downloadedAudioUrls = ConcurrentHashMap.newKeySet();

        List<String> pendingVideoFiles = Collections.synchronizedList(new ArrayList<>());
        List<String> pendingAudioFiles = Collections.synchronizedList(new ArrayList<>());

        ExecutorService downloadExecutor = Executors.newCachedThreadPool();
        BlockingQueue<DownloadResult> resultQueue = new LinkedBlockingQueue<>();

        long pollIntervalMs = 2000;
        Path finalOutput = null;
        boolean isFirstChunk = true;

        while (!task.isStopped()) {
            try {
                long loopStartTime = System.currentTimeMillis();

                String videoChunklistContent = httpGet(videoChunklistUrl);
                List<HlsParser.SegmentInfo> videoSegments = HlsParser.parseTsChunklist(videoChunklistContent);

                List<HlsParser.SegmentInfo> audioSegments = new ArrayList<>();
                if (audioChunklistUrl != null) {
                    String audioChunklistContent = httpGet(audioChunklistUrl);
                    audioSegments = HlsParser.parseTsChunklist(audioChunklistContent);
                }

                log.debug("TS格式 - 视频: {} 个分片, 音频: {} 个", videoSegments.size(), audioSegments.size());

                if (videoSegments.isEmpty()) {
                    log.warn("视频 chunklist 为空，直播可能已结束");
                    if (pendingVideoFiles.isEmpty() && pendingAudioFiles.isEmpty()) {
                        break;
                    }
                }

                // 1. 提交下载任务
                int newVideoCount = 0;
                for (HlsParser.SegmentInfo segment : videoSegments) {
                    String absoluteUrl = toAbsoluteUrl(videoChunklistUrl, segment.getUrl());
                    if (downloadedVideoUrls.add(absoluteUrl)) {
                        final String url = absoluteUrl;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "video", Paths.get(url).getFileName().toString());
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(filename, "video"));
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("下载视频分片失败 [{}]: {}", url, e.getMessage());
                            }
                        });
                        newVideoCount++;
                    }
                }

                int newAudioCount = 0;
                for (HlsParser.SegmentInfo segment : audioSegments) {
                    String absoluteUrl = toAbsoluteUrl(audioChunklistUrl, segment.getUrl());
                    if (downloadedAudioUrls.add(absoluteUrl)) {
                        final String url = absoluteUrl;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "audio", Paths.get(url).getFileName().toString());
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(filename, "audio"));
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("下载音频分片失败 [{}]: {}", url, e.getMessage());
                            }
                        });
                        newAudioCount++;
                    }
                }

                // 2. 等待本次所有新分片下载完成
                int totalNew = newVideoCount + newAudioCount;
                int downloaded = 0;
                while (downloaded < totalNew) {
                    DownloadResult result = resultQueue.poll(5, TimeUnit.SECONDS);
                    if (result != null) {
                        if ("video".equals(result.type)) {
                            pendingVideoFiles.add(result.filename);
                        } else {
                            pendingAudioFiles.add(result.filename);
                        }
                        downloaded++;
                    } else {
                        if (newVideoCount == 0 && newAudioCount == 0) break;
                    }
                }

                // 3. 本次分片下载完成后，立即合并到最终文件
                if (!pendingVideoFiles.isEmpty()) {
                    pendingVideoFiles.sort(String::compareTo);
                    pendingAudioFiles.sort(String::compareTo);

                    Path mergedFile = mergeTsSegments(tmpDir, pendingVideoFiles);

                    if (mergedFile != null && Files.exists(mergedFile)) {
                        if (isFirstChunk) {
                            finalOutput = task.getOutputDir().resolve(
                                    task.getUsername() + "_" +
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".mp4");
                            Files.createDirectories(finalOutput.getParent());

                            Path tsFile = tmpDir.resolve("chunk_merged_" + System.currentTimeMillis() + ".ts");
                            Files.copy(mergedFile, tsFile);

                            String mp4Cmd = String.format("%s -y -i \"%s\" -c copy \"%s\"",
                                    getFfmpegPath(), tsFile.toString(), finalOutput.toString());
                            runFfmpeg(mp4Cmd.split(" "), task);

                            log.info("TS格式录制开始: {}", finalOutput.getFileName());
                            task.setFinalOutputFile(finalOutput);
                            isFirstChunk = false;

                            Files.deleteIfExists(tsFile);
                        } else {
                            appendTsToFinal(mergedFile, finalOutput, task);
                        }
                    }

                    pendingVideoFiles.clear();
                    pendingAudioFiles.clear();
                }

                // 4. 轮询间隔
                long elapsed = System.currentTimeMillis() - loopStartTime;
                long sleepTime = Math.max(100, pollIntervalMs - elapsed);
                Thread.sleep(sleepTime);

            } catch (Exception e) {
                log.error("TS格式下载循环异常: {}", e.getMessage(), e);
                if (!task.isStopped()) {
                    Thread.sleep(2000);
                }
            }
        }

        downloadExecutor.shutdown();
        try {
            downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("下载线程池关闭被中断");
        }

        resultQueue.drainTo(new ArrayList<>());

        log.info("TS格式录制结束: {}", finalOutput != null ? finalOutput.getFileName() : "无输出文件");

        cleanupTempFiles(task);
    }

    // -------------------- 分片合并 --------------------

    /**
     * 合并片段到 part 文件（支持音视频）
     * <p>
     * 流程：
     * 1. 视频：二进制拼接 init + segments → video_part.mp4
     * 2. 音频：二进制拼接 init + segments → audio_part.mp4
     * 3. 混合：ffmpeg -i video_part.mp4 -i audio_part.mp4 → partN.mp4
     */
    private void mergeToPartFile(RecordingTask task, List<String> videoFiles, List<String> audioFiles) throws Exception {
        if (videoFiles.isEmpty()) {
            log.warn("没有视频分片需要合并");
            return;
        }

        int partIndex = task.getPartCount();
        Path partFile = task.getOutputDir().resolve("part" + (partIndex + 1) + ".mp4");
        boolean hasAudio = !audioFiles.isEmpty();

        // 1. 二进制拼接视频分片
        Path videoPartFile = task.getTmpDir().resolve("video_part" + (partIndex + 1) + ".mp4");
        try (OutputStream out = Files.newOutputStream(videoPartFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (task.getInitVideoSegmentFile() != null && Files.exists(task.getInitVideoSegmentFile())) {
                Files.copy(task.getInitVideoSegmentFile(), out);
                log.debug("写入视频 init: {}", task.getInitVideoSegmentFile().getFileName());
            }
            for (String filename : videoFiles) {
                Path filePath = task.getTmpDir().resolve(filename);
                if (Files.exists(filePath)) {
                    Files.copy(filePath, out);
                    log.debug("写入视频分片: {}", filename);
                } else {
                    log.warn("视频分片不存在: {}", filename);
                }
            }
        }
        log.info("二进制拼接视频: {} 个分片 -> {}", videoFiles.size(), videoPartFile.getFileName());

        if (!hasAudio) {
            Files.move(videoPartFile, partFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("无音频，视频直接作为 part: {}", partFile);
        } else {
            // 有音频：二进制拼接音频，然后混合
            Path audioPartFile = task.getTmpDir().resolve("audio_part" + (partIndex + 1) + ".mp4");
            try (OutputStream audioOut = Files.newOutputStream(audioPartFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (task.getInitAudioSegmentFile() != null && Files.exists(task.getInitAudioSegmentFile())) {
                    Files.copy(task.getInitAudioSegmentFile(), audioOut);
                    log.debug("写入音频 init: {}", task.getInitAudioSegmentFile().getFileName());
                }
                for (String filename : audioFiles) {
                    Path filePath = task.getTmpDir().resolve(filename);
                    if (Files.exists(filePath)) {
                        Files.copy(filePath, audioOut);
                        log.debug("写入音频分片: {}", filename);
                    } else {
                        log.warn("音频分片不存在: {}", filename);
                    }
                }
            }
            log.info("二进制拼接音频: {} 个分片 -> {}", audioFiles.size(), audioPartFile.getFileName());

            String[] mergeCommand = {
                    getFfmpegPath(), "-loglevel", "info", "-y",
                    "-i", videoPartFile.toAbsolutePath().toString(),
                    "-i", audioPartFile.toAbsolutePath().toString(),
                    "-c", "copy", "-map", "0:v:0", "-map", "1:a:0",
                    partFile.toAbsolutePath().toString()
            };
            log.info("混合音视频 -> {}", partFile);
            runFfmpeg(mergeCommand, task);

            Files.deleteIfExists(videoPartFile);
            Files.deleteIfExists(audioPartFile);
        }

        // 2. 删除已合并的片段文件
        for (String filename : videoFiles) {
            Files.deleteIfExists(task.getTmpDir().resolve(filename));
        }
        for (String filename : audioFiles) {
            Files.deleteIfExists(task.getTmpDir().resolve(filename));
        }

        videoFiles.clear();
        audioFiles.clear();

        task.incrementPartCount();
        log.info("Part {} 合并完成, 大小: {} bytes",
                partIndex + 1, Files.exists(partFile) ? Files.size(partFile) : 0);
    }

    /**
     * 将 part 文件实时追加到最终文件
     * 第一个 part 创建最终文件，后续 parts 用 ffmpeg concat 追加
     */
    private void appendToFinalFile(Path partFile, RecordingTask task) {
        try {
            Path targetFinal = task.getFinalOutputFile();
            if (targetFinal == null) {
                log.error("未设置最终文件路径");
                return;
            }

            Files.createDirectories(targetFinal.getParent());

            if (!Files.exists(targetFinal)) {
                Files.move(partFile, targetFinal, StandardCopyOption.REPLACE_EXISTING);
                log.info("创建最终文件: {} (from part)", targetFinal.getFileName());
            } else {
                Path concatList = Files.createTempFile("concat_", ".txt");
                try (BufferedWriter writer = Files.newBufferedWriter(concatList, StandardOpenOption.CREATE)) {
                    writer.write("file '" + targetFinal.toAbsolutePath() + "'");
                    writer.newLine();
                    writer.write("file '" + partFile.toAbsolutePath() + "'");
                    writer.newLine();
                }

                Path tempOutput = Files.createTempFile("temp_merge_", ".mp4");
                String concatCmd = String.format("%s -y -f concat -safe 0 -i \"%s\" -c copy \"%s\"",
                        getFfmpegPath(), concatList.toString(), tempOutput.toString());

                log.debug("追加 part 到最终文件: {}", partFile.getFileName());
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", concatCmd);
                int exitCode = pb.start().waitFor();

                if (exitCode == 0) {
                    Files.move(tempOutput, targetFinal, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(partFile);
                    log.info("Part 已追加并删除: {} -> {} (total {} bytes)",
                            partFile.getFileName(), targetFinal.getFileName(), Files.size(targetFinal));
                } else {
                    log.error("追加 part 失败, exitCode={}", exitCode);
                    Files.deleteIfExists(tempOutput);
                }

                Files.deleteIfExists(concatList);
            }
        } catch (Exception e) {
            log.error("追加到最终文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 合并所有 part 文件为最终输出文件
     */
    private void mergeAllParts(RecordingTask task) throws Exception {
        if (task.getPartCount() == 0) {
            log.warn("没有 part 文件需要合并");
            return;
        }

        Path finalOutput = task.getFinalOutputFile();
        if (finalOutput == null) {
            log.error("未设置最终文件路径");
            return;
        }

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

        StringBuilder concatInput = new StringBuilder("concat:");
        for (int i = 0; i < partFiles.size(); i++) {
            if (i > 0) concatInput.append("|");
            concatInput.append(partFiles.get(i).toAbsolutePath().toString());
        }

        String[] command = {
                getFfmpegPath(), "-loglevel", "info", "-y",
                "-i", concatInput.toString(),
                "-c", "copy",
                finalOutput.toAbsolutePath().toString()
        };

        log.info("合并所有 part 文件: {} parts -> {}", partFiles.size(), finalOutput);
        runFfmpeg(command, task);

        for (Path partFile : partFiles) {
            Files.deleteIfExists(partFile);
            log.debug("删除 part 文件: {}", partFile.getFileName());
        }

        log.info("最终合并完成: {}", finalOutput);
    }

    /**
     * 合并 TS 视频和音频分片为一个文件
     */
    private Path mergeTsSegments(Path tmpDir, List<String> videoFiles) throws Exception {
        if (videoFiles.isEmpty()) return null;

        Path concatList = tmpDir.resolve("concat_" + System.currentTimeMillis() + ".txt");
        try (BufferedWriter writer = Files.newBufferedWriter(concatList)) {
            for (String filename : videoFiles) {
                writer.write("file '" + tmpDir.resolve(filename).toAbsolutePath() + "'");
                writer.newLine();
            }
        }

        Path mergedTs = tmpDir.resolve("merged_" + System.currentTimeMillis() + ".ts");
        String cmd = String.format("%s -y -f concat -safe 0 -i \"%s\" -c copy \"%s\"",
                getFfmpegPath(), concatList.toString(), mergedTs.toString());

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        int exit = pb.start().waitFor();
        Files.deleteIfExists(concatList);

        if (exit == 0) {
            for (String filename : videoFiles) {
                Files.deleteIfExists(tmpDir.resolve(filename));
            }
            return mergedTs;
        } else {
            log.error("TS合并失败, exitCode={}", exit);
            return null;
        }

    }

    /**
     * 将合并后的 TS 文件追加到最终 MP4 文件
     */
    private void appendTsToFinal(Path newTsFile, Path finalMp4, RecordingTask task) throws Exception {
        Path concatList = task.getTmpDir().resolve("append_concat.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(concatList)) {
            writer.write("file '" + finalMp4.toAbsolutePath() + "'");
            writer.newLine();
            writer.write("file '" + newTsFile.toAbsolutePath() + "'");
            writer.newLine();
        }

        Path tempOutput = task.getTmpDir().resolve("temp_append_" + System.currentTimeMillis() + ".mp4");
        String cmd = String.format("%s -y -f concat -safe 0 -i \"%s\" -c copy \"%s\"",
                getFfmpegPath(), concatList.toString(), tempOutput.toString());

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        int exit = pb.start().waitFor();
        Files.deleteIfExists(concatList);

        if (exit == 0) {
            Files.deleteIfExists(finalMp4);
            Files.move(tempOutput, finalMp4);
            log.debug("已追加 TS 到最终文件: {} bytes", Files.size(finalMp4));
        } else {
            log.error("追加 TS 到最终文件失败, exitCode={}", exit);
            Files.deleteIfExists(tempOutput);
        }

        Files.deleteIfExists(newTsFile);
    }

    // -------------------- Init 片段处理 --------------------

    private void downloadInitSegments(RecordingTask task, String videoChunklistUrl,
                                        String audioChunklistUrl, Path tmpDir) {
        try {
            String videoChunklistContent = httpGet(videoChunklistUrl);
            log.debug("视频 chunklist 内容前 500 字符:\n{}",
                    videoChunklistContent.length() > 500 ? videoChunklistContent.substring(0, 500) : videoChunklistContent);
            Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent);
            String videoInitUrl = (String) videoParseResult.get("initSegmentUrl");

            if (videoInitUrl != null) {
                String initUrl = toAbsoluteUrl(videoChunklistUrl, videoInitUrl);
                String filename = downloadSegment(initUrl, tmpDir);
                Path initFile = tmpDir.resolve(filename);
                task.setInitVideoSegmentFile(initFile);
                log.info("下载视频 init 片段: {} -> {}", videoInitUrl, initFile);
            } else {
                log.warn("未从视频 chunklist 解析到 EXT-X-MAP，init 片段可能不存在");
            }

            if (audioChunklistUrl != null) {
                String audioChunklistContent = httpGet(audioChunklistUrl);
                Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent);
                String audioInitUrl = (String) audioParseResult.get("initSegmentUrl");

                if (audioInitUrl != null) {
                    String initUrl = toAbsoluteUrl(audioChunklistUrl, audioInitUrl);
                    String filename = downloadSegment(initUrl, tmpDir);
                    Path initFile = tmpDir.resolve(filename);
                    task.setInitAudioSegmentFile(initFile);
                    log.info("下载音频 init 片段: {} -> {}", audioInitUrl, initFile);
                }
            }
        } catch (Exception e) {
            log.error("下载 init 片段失败: {}", e.getMessage(), e);
        }
    }

    private void ensureInitSegments(RecordingTask task, String videoChunklistUrl,
                                     String audioChunklistUrl, Path tmpDir) {
        Path videoInit = task.getInitVideoSegmentFile();
        if (!isValidInitFile(videoInit)) {
            log.warn("视频 init 文件无效或过期，重新获取: {}", videoInit);
            try {
                String videoChunklistContent = httpGet(videoChunklistUrl);
                Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent);
                String videoInitUrl = (String) videoParseResult.get("initSegmentUrl");
                if (videoInitUrl != null) {
                    String initUrl = toAbsoluteUrl(videoChunklistUrl, videoInitUrl);
                    String filename = downloadSegment(initUrl, tmpDir);
                    task.setInitVideoSegmentFile(tmpDir.resolve(filename));
                    log.info("重新下载视频 init 片段: {} -> {}", initUrl, filename);
                }
            } catch (Exception e) {
                log.error("重新下载视频 init 片段失败: {}", e.getMessage());
            }
        }

        Path audioInit = task.getInitAudioSegmentFile();
        if (!isValidInitFile(audioInit) && audioChunklistUrl != null) {
            log.warn("音频 init 文件无效或过期，重新获取: {}", audioInit);
            try {
                String audioChunklistContent = httpGet(audioChunklistUrl);
                Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent);
                String audioInitUrl = (String) audioParseResult.get("initSegmentUrl");
                if (audioInitUrl != null) {
                    String initUrl = toAbsoluteUrl(audioChunklistUrl, audioInitUrl);
                    String filename = downloadSegment(initUrl, tmpDir);
                    task.setInitAudioSegmentFile(tmpDir.resolve(filename));
                    log.info("重新下载音频 init 片段: {} -> {}", initUrl, filename);
                }
            } catch (Exception e) {
                log.error("重新下载音频 init 片段失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查 init 文件是否有效（包含 ftyp box）
     */
    private boolean isValidInitFile(Path initFile) {
        if (initFile == null || !Files.exists(initFile)) return false;
        try {
            byte[] header = new byte[12];
            try (FileInputStream fis = new FileInputStream(initFile.toFile())) {
                int read = fis.read(header);
                if (read < 12) return false;
            }
            String ftyp = new String(header, 4, 4);
            return "ftyp".equals(ftyp);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------- 文件操作工具 --------------------

    /**
     * 清理临时文件和文件夹
     */
    private void cleanupTempFiles(RecordingTask task) {
        Path tmpDir = task.getTmpDir();
        Path outputDir = task.getOutputDir();

        if (tmpDir != null && Files.exists(tmpDir)) {
            try {
                deleteDirectory(tmpDir);
                log.info("已删除临时目录: {}", tmpDir);
            } catch (Exception e) {
                log.warn("删除临时目录失败: {} - {}", tmpDir, e.getMessage());
            }
        }

        if (outputDir != null && Files.exists(outputDir)) {
            try {
                if (isDirectoryEmpty(outputDir)) {
                    Files.delete(outputDir);
                    log.info("已删除空目录: {}", outputDir);
                } else {
                    log.debug("输出目录不为空，保留: {} (内含: {})", outputDir, listDirectoryContents(outputDir));
                }
            } catch (Exception e) {
                log.warn("删除输出目录失败: {} - {}", outputDir, e.getMessage());
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.comparing(Path::toString).reversed())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {} - {}", p, e.getMessage());
                        }
                    });
        }
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    private String listDirectoryContents(Path dir) {
        try (var entries = Files.list(dir)) {
            return entries.map(Path::getFileName).map(Path::toString)
                    .collect(Collectors.joining(", "));
        } catch (IOException e) {
            return "<无法读取>";
        }
    }

    // -------------------- 网络请求 --------------------

    /**
     * 下载单个片段
     */
    private String downloadSegment(String segmentUrl, Path tmpDir) throws Exception {
        String filename = segmentUrl.substring(segmentUrl.lastIndexOf("/") + 1);
        int queryIndex = filename.indexOf("?");
        if (queryIndex > 0) {
            filename = filename.substring(0, queryIndex);
        }
        Path outputPath = tmpDir.resolve(filename);

        if (Files.exists(outputPath)) {
            log.debug("片段已存在，跳过: {}", filename);
            return filename;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(segmentUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            conn.disconnect();
        }

        log.debug("下载完成: {}", filename);
        return filename;
    }

    /**
     * HTTP GET 请求（使用 Java 原生 HttpURLConnection）
     * 当返回 403 时抛出 UrlExpiredException
     */
    private String httpGet(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            int statusCode = conn.getResponseCode();

            if (statusCode == 403) {
                conn.disconnect();
                throw new UrlExpiredException("URL 返回 403，Token 可能已过期: " + urlStr, urlStr, 403);
            }
            if (statusCode != 200) {
                conn.disconnect();
                throw new UrlExpiredException("http请求失败，状态码：" + statusCode, urlStr, statusCode);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                return response.toString();
            } finally {
                conn.disconnect();
            }
        } catch (UrlExpiredException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("HTTP GET failed: " + urlStr, e);
        }
    }

    /**
     * 将相对路径转换为绝对 URL
     */
    private String toAbsoluteUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl.startsWith("http")) {
            return relativeUrl;
        }
        if (relativeUrl.startsWith("/")) {
            int protoIdx = baseUrl.indexOf("://");
            if (protoIdx > 0) {
                String base = baseUrl.substring(0, baseUrl.indexOf("/", protoIdx + 3));
                return base + relativeUrl;
            }
        } else {
            String base = baseUrl.substring(0, baseUrl.lastIndexOf("/") + 1);
            return base + relativeUrl;
        }
        return relativeUrl;
    }

    // -------------------- ffmpeg --------------------

    /**
     * 运行 ffmpeg 命令
     */
    private void runFfmpeg(String[] command, RecordingTask task) throws Exception {
        log.info("执行 ffmpeg: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
}
