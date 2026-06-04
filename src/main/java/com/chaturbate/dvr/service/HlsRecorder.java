package com.chaturbate.dvr.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.chaturbate.dvr.dto.ChatVideoContext;
import com.chaturbate.dvr.dto.DownloadResult;
import com.chaturbate.dvr.exception.UrlExpiredException;
import com.chaturbate.dvr.task.RecordingTask;
import com.chaturbate.dvr.utils.HlsParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
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

    /**
     * 跟踪每个 RecordingTask 的永久失败 msn（下载结果为 404/永久丢失）。
     * 用于 Merger 检测到卡死时直接跳过，而非等待不存在的分片。
     * 结构：taskId → ConcurrentHashMap(msn → failureTimestamp)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> permanentFailedMsns = new ConcurrentHashMap<>();

    private ConcurrentHashMap<Long, Long> getPermanentFailures(RecordingTask task) {
        return permanentFailedMsns.computeIfAbsent(task.getTaskId(), k -> new ConcurrentHashMap<>());
    }

    // 配置项（从数据库加载，带默认值）
    private String getRecordPath() {
        return configService.getConfigValue("record_path", "./recordings");
    }

    /**
     * 解析录制路径中的占位符
     * 支持：
     * {username}           - 主播用户名
     * {yyyy-mm-dd}        - 当前日期
     * {yyyy-mm-dd-HH-MM-SS} - 当前时间（年月日时分秒）
     * 示例：./recordings/{username}/{yyyy-mm-dd-HH-MM-SS} -> ./recordings/streamer_name/2026-06-03-22-38-00
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
        String dateTimeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        result = result.replace("{yyyy-mm-dd-HH-MM-SS}", dateTimeStr);
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

    /** 构建跨平台 shell 命令（Windows: cmd /c, Linux/macOS: sh -c） */
    private List<String> shellCmd(String command) {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().startsWith("win")) {
            cmd.add("cmd");
            cmd.add("/c");
        } else {
            cmd.add("sh");
            cmd.add("-c");
        }
        cmd.add(command);
        log.info("执行命令：{}",cmd);
        return cmd;
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
                // 只移除自己的任务（避免旧任务结束时把新任务从 map 中移除）
                if (activeRecordings.get(username) == task) {
                    activeRecordings.remove(username);
                }
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
        task.refreshThreadPoolStats();
        info.put("downloadedSegments", task.getDownloadedSegments());
        info.put("submittedSegments", task.getSubmittedSegments());
        info.put("threadPoolActiveCount", task.getThreadPoolActiveCount());
        info.put("threadPoolQueueSize", task.getThreadPoolQueueSize());

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
                if (currentAudioUrl[0] != null) {
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
                    downloadAndMergeLoopTs(task, currentVideoUrl[0], tmpDir);
                } else {
                    downloadAndMergeLoopM4s(task, currentVideoUrl[0], currentAudioUrl[0], tmpDir);
                }

        // 5. 录制结束，合并所有 part 文件（仅 fMP4 需要，TS 已在循环中合并）
        // 注意：appendToFinalFile 已经增量追加了所有 part，此处仅作保底
        if ("fmp4".equals(task.getFormat()) && task.getPartCount() > 0) {
            Path finalOutput = task.getFinalOutputFile();
            if (finalOutput != null && Files.exists(finalOutput) && Files.size(finalOutput) > 0) {
                log.info("录制 [{}] 完成, 文件: {}, 大小: {} bytes (已通过 appendToFinalFile 增量追加, 跳过 mergeAllParts)",
                        task.getUsername(), finalOutput, Files.size(finalOutput));
            } else {
                mergeAllParts(task);
                log.info("录制 [{}] 完成, 文件: {}, 大小: {} bytes",
                        task.getUsername(), task.getFinalOutputFile(),
                        Files.exists(task.getFinalOutputFile()) ? Files.size(task.getFinalOutputFile()) : 0);
            }
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
                    // 新 chunklist 有自己的 init m4s，必须重新下载
                    task.setInitVideoSegmentFile(null);
                    task.setInitAudioSegmentFile(null);
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

        List<String> pendingVideoFiles = new CopyOnWriteArrayList<>();
        List<String> pendingAudioFiles = new CopyOnWriteArrayList<>();
        Map<Long, String> videoByMsn = new ConcurrentHashMap<>();
        Map<Long, String> audioByMsn = new ConcurrentHashMap<>();

        BlockingQueue<DownloadResult> resultQueue = new LinkedBlockingQueue<>();
        ExecutorService downloadExecutor = Executors.newCachedThreadPool();
        final long basePollInterval = 2000L;

        task.setDownloadExecutor(downloadExecutor);
        downloadInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);

        // ── Merger 线程 ───────────────────────────────────────────────────────
        Thread mergerThread = new Thread(() -> mergerLoop(task, resultQueue, videoByMsn, audioByMsn,
                pendingVideoFiles, pendingAudioFiles, videoChunklistUrl, audioChunklistUrl,
                tmpDir, basePollInterval), "Merger-" + task.getUsername());
        mergerThread.start();

        // ── 主循环：轮询 chunklist → 提交下载 ────────────────────────────────
        while (!task.isStopped()) {
            try {
                LlHlsChunklists chunklists = fetchChunklistsForM4s(task, videoChunklistUrl, audioChunklistUrl);

                if (chunklists.videoSegments().isEmpty()) {
                    log.warn("视频 chunklist 为空，直播可能已结束");
                    Thread.sleep(chunklists.pollIntervalMs());
                    continue;
                }

                int newVideo = submitVideoM4sDownloads(
                        chunklists.videoSegments(), videoChunklistUrl, task, tmpDir,
                        downloadedVideoUrls, downloadExecutor, resultQueue);
                int newAudio = submitAudioM4sDownloads(
                        chunklists.audioSegments(), audioChunklistUrl, task, tmpDir,
                        downloadedAudioUrls, downloadExecutor, resultQueue);

                if (newVideo > 0 || newAudio > 0) {
                    log.info("提交下载: {} 视频, {} 音频 (Merger 待处理: {})",
                            newVideo, newAudio, pendingVideoFiles.size() + pendingAudioFiles.size());
                }

                Thread.sleep(chunklists.pollIntervalMs());

            } catch (InterruptedException e) {
                break;
            } catch (UrlExpiredException e) {
                // 403/401：chunklist URL 已过期，抛给 doRecording 刷新 master.m3u8
                throw e;
            } catch (Exception e) {
                log.error("Chunklist 轮询异常: {}，{}ms 后重试", e.getMessage(), basePollInterval, e);
                Thread.sleep(basePollInterval);
            }
        }

        // ── 退出清理 ─────────────────────────────────────────────────────────
        try {
            mergerThread.interrupt();
            mergerThread.join(5000);
        } catch (InterruptedException e) {
            log.warn("Merger 线程等待被中断");
        }
        downloadExecutor.shutdown();
        downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);

        Path finalOutput = task.getFinalOutputFile();
        if (finalOutput != null && Files.exists(finalOutput)) {
            log.info("录制完成: {} ({} bytes)", finalOutput.getFileName(), Files.size(finalOutput));
        } else {
            log.warn("录制结束但未生成最终文件");
        }
        cleanupTempFiles(task);
    }

    /**
     * Merger 线程主体：消费 resultQueue，等待配对，按 msn 顺序合并
     *
     * 策略：
     * - 用 nextExpectedMsn 追踪下一个应合并的序列号
     * - 只在 msn == nextExpectedMsn 时配对合并（保证顺序）
     * - 乱序到达的 segment 留在 pending map，等前面的补齐后再处理
     * - 录制结束时 flush 所有 pending
     */
    private void mergerLoop(RecordingTask task,
                            BlockingQueue<DownloadResult> resultQueue,
                            Map<Long, String> videoByMsn,
                            Map<Long, String> audioByMsn,
                            List<String> pendingVideoFiles,
                            List<String> pendingAudioFiles,
                            String videoChunklistUrl,
                            String audioChunklistUrl,
                            Path tmpDir,
                            long basePollInterval) {
        long nextExpectedMsn = -1;
        int lastLogged = 0;
        int flushCounter = 0;
        long lastMergeTimestamp = 0;

        while (!task.isStopped() || !resultQueue.isEmpty()) {
            try {
                DownloadResult result = resultQueue.poll(100, TimeUnit.MILLISECONDS);
                if (result == null) {
                    handleMergerStall(task, resultQueue, videoByMsn, audioByMsn,
                            pendingVideoFiles, pendingAudioFiles, nextExpectedMsn, lastMergeTimestamp, basePollInterval);
                    // 持续合并直到没有可配对的（解决视频慢于音频时的 stall 问题）
                    while (nextExpectedMsn >= 0) {
                        // 检查永久失败：如果 nextExpectedMsn 已永久失败（但不在 maps 中），直接跳过
                        ConcurrentHashMap<Long, Long> pfail = getPermanentFailures(task);
                        if (pfail.containsKey(nextExpectedMsn)
                                && !videoByMsn.containsKey(nextExpectedMsn)
                                && !audioByMsn.containsKey(nextExpectedMsn)) {
                            log.warn("Merger 跳过永久失败的 msn={}（永久失败标记，但分片未到达）", nextExpectedMsn);
                            nextExpectedMsn++;
                            lastMergeTimestamp = System.currentTimeMillis();
                            continue;
                        }
                        long newMsn = attemptMergeOne(task, videoByMsn, audioByMsn,
                                pendingVideoFiles, pendingAudioFiles, nextExpectedMsn,
                                videoChunklistUrl, audioChunklistUrl, tmpDir);
                        if (newMsn == nextExpectedMsn) break; // 无法推进，退出循环
                        nextExpectedMsn = newMsn;
                    }
                    if (task.isStopped() && resultQueue.isEmpty()) {
                        flushRemainingParts(task, videoByMsn, audioByMsn,
                                pendingVideoFiles, pendingAudioFiles,
                                videoChunklistUrl, audioChunklistUrl, tmpDir);
                    }
                    continue;
                }

                if (!result.success) {
                    handleFailedResult(result, videoByMsn, audioByMsn, pendingVideoFiles, pendingAudioFiles);
                    // 如果视频和音频都永久失败于同一 msn，立即跳过该 msn，避免 Merger 永远卡在此处
                    long failedMsn = extractMsnFromFilename(result.filename);
                    ConcurrentHashMap<Long, Long> pfail = getPermanentFailures(task);
                    if (pfail.containsKey(failedMsn)) {
                        videoByMsn.remove(failedMsn);
                        audioByMsn.remove(failedMsn);
                        pendingVideoFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
                        pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
                        log.warn("Merger 强制跳过永久失败的 msn={}（视频+音频均永久失败）", failedMsn);
                        if (nextExpectedMsn == failedMsn) {
                            nextExpectedMsn = failedMsn + 1;
                        }
                    }
                    continue;
                }

                long msn = extractMsnFromFilename(result.filename);
                if (msn < 0) { log.warn("Merger 无法从文件名提取 msn: {}", result.filename); continue; }

                if ("video".equals(result.type)) {
                    pendingVideoFiles.add(result.filename);
                    videoByMsn.put(msn, result.filename);
                } else {
                    pendingAudioFiles.add(result.filename);
                    audioByMsn.put(msn, result.filename);
                }

                // 初始化 nextExpectedMsn
                if (nextExpectedMsn < 0) {
                    if (!videoByMsn.isEmpty() && !audioByMsn.isEmpty()) {
                        nextExpectedMsn = Math.max(Collections.min(videoByMsn.keySet()), Collections.min(audioByMsn.keySet()));
                    } else if (!videoByMsn.isEmpty()) {
                        nextExpectedMsn = Collections.min(videoByMsn.keySet());
                    }
                }

                // 推进合并
                boolean madeProgress;
                do {
                    madeProgress = false;
                    while (videoByMsn.containsKey(nextExpectedMsn) && audioByMsn.containsKey(nextExpectedMsn)) {
                        mergeOnePart(task, videoByMsn, audioByMsn,
                                pendingVideoFiles, pendingAudioFiles, nextExpectedMsn,
                                videoChunklistUrl, audioChunklistUrl, tmpDir);
                        nextExpectedMsn++;
                        madeProgress = true;
                        lastMergeTimestamp = System.currentTimeMillis();
                    }
                    // 视频领先音频：前移到两端都有
                    if (!madeProgress && nextExpectedMsn >= 0
                            && videoByMsn.containsKey(nextExpectedMsn) && !audioByMsn.containsKey(nextExpectedMsn)) {
                        long minAudio = Collections.min(audioByMsn.keySet());
                        if (videoByMsn.containsKey(minAudio) && minAudio > nextExpectedMsn) {
                            log.debug("Merger 自动跳转 msn {} → {}（视频领先，音频尚未到达）", nextExpectedMsn, minAudio);
                            nextExpectedMsn = minAudio;
                            continue;
                        }
                    }
                    // 音频领先视频：前移到两端都有
                    if (!madeProgress && nextExpectedMsn >= 0
                            && !videoByMsn.containsKey(nextExpectedMsn) && audioByMsn.containsKey(nextExpectedMsn)) {
                        long minVideo = Collections.min(videoByMsn.keySet());
                        if (minVideo > nextExpectedMsn) {
                            // 视频的 min 大于 nextExpected，直接跳到视频起始位置
                            log.warn("Merger 自动跳转 msn {} → {}（音频领先，视频尚未到达/缺失）", nextExpectedMsn, minVideo);
                            final long minV = minVideo;
                            audioByMsn.keySet().removeIf(k -> k < minV);
                            pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) < minV);
                            nextExpectedMsn = minVideo;
                            continue;
                        }
                        // 视频有比 nextExpected 更大的 msn，找下一个视频和音频都有的
                        final long currentExpected = nextExpectedMsn;
                        for (long candidateMsn : videoByMsn.keySet().stream().filter(k -> k > currentExpected).sorted().collect(Collectors.toList())) {
                            if (audioByMsn.containsKey(candidateMsn)
                                    && Files.exists(task.getTmpDir().resolve(videoByMsn.get(candidateMsn)))
                                    && Files.exists(task.getTmpDir().resolve(audioByMsn.get(candidateMsn)))) {
                                log.warn("Merger 跳过缺失视频的 msn {} → {}（音频领先，视频永久缺失）", currentExpected, candidateMsn);
                                // 清理跳过的音频
                                for (long s = currentExpected; s < candidateMsn; s++) {
                                    final long skipMsn = s;
                                    audioByMsn.remove(skipMsn);
                                    pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == skipMsn);
                                }
                                nextExpectedMsn = candidateMsn;
                                madeProgress = true;
                                break;
                            }
                        }
                        if (madeProgress) {
                            lastMergeTimestamp = System.currentTimeMillis();
                            continue;
                        }
                    }
                    // 永久丢失分片：尝试 nextExpectedMsn+1
                    if (!madeProgress && nextExpectedMsn >= 0
                            && !videoByMsn.containsKey(nextExpectedMsn) && !audioByMsn.containsKey(nextExpectedMsn)) {
                        long skipMsn = nextExpectedMsn + 1;
                        if (videoByMsn.containsKey(skipMsn) && audioByMsn.containsKey(skipMsn)
                                && Files.exists(task.getTmpDir().resolve(videoByMsn.get(skipMsn)))
                                && Files.exists(task.getTmpDir().resolve(audioByMsn.get(skipMsn)))) {
                            log.warn("Merger 跳过 msn={}（缺少 msn={}，文件可能永久丢失）", skipMsn, nextExpectedMsn);
                            mergeOnePart(task, videoByMsn, audioByMsn,
                                    pendingVideoFiles, pendingAudioFiles, skipMsn,
                                    videoChunklistUrl, audioChunklistUrl, tmpDir);
                            nextExpectedMsn = skipMsn + 1;
                            madeProgress = true;
                            lastMergeTimestamp = System.currentTimeMillis();
                        }
                    }
                } while (madeProgress);

                int total = pendingVideoFiles.size() + pendingAudioFiles.size();
                if (total / 10 > lastLogged) {
                    lastLogged = total / 10;
                    log.info("Merger 待合并: 视频 {} 个(msn {}~{}), 音频 {} 个(msn {}~{}), nextExpected={}",
                            pendingVideoFiles.size(),
                            videoByMsn.isEmpty() ? -1 : Collections.min(videoByMsn.keySet()),
                            videoByMsn.isEmpty() ? -1 : Collections.max(videoByMsn.keySet()),
                            pendingAudioFiles.size(),
                            audioByMsn.isEmpty() ? -1 : Collections.min(audioByMsn.keySet()),
                            audioByMsn.isEmpty() ? -1 : Collections.max(audioByMsn.keySet()),
                            nextExpectedMsn);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.error("Merger 线程异常: {}", e.getMessage(), e);
            }
        }
        log.info("Merger 线程退出 (共合并 {} parts)", flushCounter);
    }

    private void handleMergerStall(RecordingTask task,
                                   BlockingQueue<DownloadResult> resultQueue,
                                   Map<Long, String> videoByMsn,
                                   Map<Long, String> audioByMsn,
                                   List<String> pendingVideoFiles,
                                   List<String> pendingAudioFiles,
                                   long nextExpectedMsn,
                                   long lastMergeTimestamp,
                                   long basePollInterval) {
        if (nextExpectedMsn < 0 || videoByMsn.isEmpty()) return;
        long now = System.currentTimeMillis();
        if ((now - lastMergeTimestamp) > basePollInterval * 3) {
            long minMsn = Collections.min(videoByMsn.keySet());
            if (nextExpectedMsn < minMsn) {
                boolean videoPending = pendingVideoFiles.stream().anyMatch(f -> extractMsnFromFilename(f) == nextExpectedMsn);
                boolean audioPending = pendingAudioFiles.stream().anyMatch(f -> extractMsnFromFilename(f) == nextExpectedMsn);
                if (!videoPending && !audioPending) {
                    long gap = minMsn - nextExpectedMsn;
                    if (gap > 20) {
                        log.warn("Merger 检测到大间隙 msn {}->{}（共 {} 个），前方 chunks 未下载（chunklist 刷新或 403），跳过",
                                nextExpectedMsn, minMsn, gap);
                    } else {
                        log.warn("Merger msn={} 永久缺失，强制跳过到 msn={}", nextExpectedMsn, minMsn);
                    }
                    audioByMsn.remove(nextExpectedMsn);
                    pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == nextExpectedMsn);
                }
            } else if (!videoByMsn.containsKey(nextExpectedMsn)) {
                // nextExpectedMsn >= minMsn 但视频没有该 msn（音频可能有）
                // 检查是否还在下载中
                boolean videoPending = pendingVideoFiles.stream().anyMatch(f -> extractMsnFromFilename(f) == nextExpectedMsn);
                if (!videoPending) {
                    // 找下一个视频和音频都有的 msn
                    final long expMsn = nextExpectedMsn;
                    for (long msn : videoByMsn.keySet().stream().filter(k -> k > expMsn).sorted().collect(Collectors.toList())) {
                        if (audioByMsn.containsKey(msn)) {
                            log.warn("Merger msn={} 视频永久缺失，强制跳过到 msn={}", nextExpectedMsn, msn);
                            for (long s = expMsn; s < msn; s++) {
                                final long skipMsn = s;
                                audioByMsn.remove(skipMsn);
                                pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == skipMsn);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * 尝试合并一个 part。
     * @return 合并后的 nextExpectedMsn（+1 如果合并成功，+0 如果无法合并）
     */
    private long attemptMergeOne(RecordingTask task,
                                 Map<Long, String> videoByMsn,
                                 Map<Long, String> audioByMsn,
                                 List<String> pendingVideoFiles,
                                 List<String> pendingAudioFiles,
                                 long nextExpectedMsn,
                                 String videoChunklistUrl,
                                 String audioChunklistUrl,
                                 Path tmpDir) {
        if (nextExpectedMsn < 0) return nextExpectedMsn;
        if (videoByMsn.containsKey(nextExpectedMsn) && audioByMsn.containsKey(nextExpectedMsn)) {
            if (!Files.exists(tmpDir.resolve(videoByMsn.get(nextExpectedMsn)))) {
                log.warn("Merger msn={} 文件已不存在（过期），跳过", nextExpectedMsn);
                audioByMsn.remove(nextExpectedMsn);
                return nextExpectedMsn + 1; // 视为已处理（跳过）
            } else {
                try {
                    mergeOnePart(task, videoByMsn, audioByMsn,
                            pendingVideoFiles, pendingAudioFiles, nextExpectedMsn,
                            videoChunklistUrl, audioChunklistUrl, tmpDir);
                    return nextExpectedMsn + 1;
                } catch (Exception e) {
                    log.error("Merger mergeOnePart 异常 msn={}: {}", nextExpectedMsn, e.getMessage(), e);
                }
            }
        }
        return nextExpectedMsn; // 无法合并，返回不变
    }

    private void handleFailedResult(DownloadResult result,
                                    Map<Long, String> videoByMsn,
                                    Map<Long, String> audioByMsn,
                                    List<String> pendingVideoFiles,
                                    List<String> pendingAudioFiles) {
        long failedMsn = extractMsnFromFilename(result.filename);
        log.warn("Merger 跳过失败分片 msn={}（{}）", failedMsn, result.type);
        if ("video".equals(result.type)) {
            videoByMsn.remove(failedMsn);
            pendingVideoFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
        } else {
            audioByMsn.remove(failedMsn);
            pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
        }
    }

    /**
     * Merger 线程调用的单次 part 合并 + 追加到最终文件
     */
    private void mergeOnePart(RecordingTask task,
                              Map<Long, String> videoByMsn,
                              Map<Long, String> audioByMsn,
                              List<String> pendingVideoFiles,
                              List<String> pendingAudioFiles,
                              long msn,
                              String videoChunklistUrl,
                              String audioChunklistUrl,
                              Path tmpDir) {
        String videoFilename = videoByMsn.get(msn);
        String audioFilename = audioByMsn.get(msn);
        if (videoFilename == null || audioFilename == null) return;

        Path videoPath = task.getTmpDir().resolve(videoFilename);
        Path audioPath = task.getTmpDir().resolve(audioFilename);
        if (!Files.exists(videoPath) || !Files.exists(audioPath)) {
            log.warn("Merger 跳过 msn={}（文件缺失 video={}, audio={})",
                    msn, Files.exists(videoPath), Files.exists(audioPath));
            return;
        }

        try {
            ensureInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);
            List<String> v = new ArrayList<>();
            v.add(videoFilename);
            List<String> a = new ArrayList<>();
            a.add(audioFilename);
            mergeToPartFile(task, v, a);

            Path partFile = task.getOutputDir().resolve("part" + task.getPartCount() + ".mp4");
            Path existingFinal = task.getFinalOutputFile();
            if (existingFinal == null) {
                Path newFinal = task.getOutputDir()
                        .resolve(task.getUsername() + "_" + LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".mp4");
                task.setFinalOutputFile(newFinal);
                existingFinal = newFinal;
            }
            appendToFinalFile(partFile, task);

            // 从 pending 列表移除（防止泄漏到 flushRemainingParts）
            pendingVideoFiles.remove(videoFilename);
            pendingAudioFiles.remove(audioFilename);
            videoByMsn.remove(msn);
            audioByMsn.remove(msn);

            log.info("Merger 合并 part msn={}，累计 {} parts", msn, task.getPartCount());
        } catch (Exception e) {
            log.error("Merger 合并 msn={} 失败: {}", msn, e.getMessage(), e);
        }
    }

    /**
     * 录制结束时 flush 所有剩余未配对的 segments
     * （理想情况下下次 chunklist 刷新后配对会补齐，此处作保底清理）
     */
    private void flushRemainingParts(RecordingTask task,
                                     Map<Long, String> videoByMsn,
                                     Map<Long, String> audioByMsn,
                                     List<String> pendingVideoFiles,
                                     List<String> pendingAudioFiles,
                                     String videoChunklistUrl,
                                     String audioChunklistUrl,
                                     Path tmpDir) {
        if (videoByMsn.isEmpty() || audioByMsn.isEmpty()) {
            log.info("flushRemaining: 无剩余配对需要处理");
            return;
        }
        // 配对：取两者 msn 的交集
        Set<Long> commonMsn = new TreeSet<>(videoByMsn.keySet());
        commonMsn.retainAll(audioByMsn.keySet());
        if (commonMsn.isEmpty()) {
            log.warn("flushRemaining: 无可配对的 msn（视频 {} 个, 音频 {} 个）",
                    videoByMsn.size(), audioByMsn.size());
            return;
        }
        log.info("flushRemaining: 开始处理 {} 个剩余 msn 配对", commonMsn.size());
        for (Long msn : commonMsn) {
            mergeOnePart(task, videoByMsn, audioByMsn, pendingVideoFiles, pendingAudioFiles,
                    msn, videoChunklistUrl, audioChunklistUrl, tmpDir);
        }
    }

    // -------------------- TS 下载合并循环 --------------------

    /**
     * 下载并合并循环（TS 格式 - 传统 HLS）
     * Chaturbate TS 格式音视频在同一流中，无独立音频 chunklist
     * 实时二进制追加到 final.ts，录制结束后转 MP4
     */
    private void downloadAndMergeLoopTs(RecordingTask task, String videoChunklistUrl, Path tmpDir) throws Exception {

        Set<String> downloadedUrls = ConcurrentHashMap.newKeySet();
        List<String> pendingFiles = Collections.synchronizedList(new ArrayList<>());
        ExecutorService downloadExecutor = Executors.newCachedThreadPool();
        task.setDownloadExecutor(downloadExecutor);
        BlockingQueue<String> resultQueue = new LinkedBlockingQueue<>();

        long pollIntervalMs = 2000;

        // 最终 TS 文件（实时追加）
        Path finalTs = task.getOutputDir().resolve(task.getUsername() + "_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".ts");
        Files.createDirectories(finalTs.getParent());

        while (!task.isStopped()) {
            try {
                long loopStartTime = System.currentTimeMillis();

                String chunklistContent = httpGet(videoChunklistUrl);
                List<HlsParser.SegmentInfo> segments = HlsParser.parseTsChunklist(chunklistContent);

                if (segments.isEmpty()) {
                    log.warn("TS chunklist 为空，直播可能已结束");
                    if (pendingFiles.isEmpty()) break;
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                // 提交新增分片下载
                int newCount = 0;
                for (HlsParser.SegmentInfo segment : segments) {
                    String absoluteUrl = toAbsoluteUrl(videoChunklistUrl, segment.getUrl());
                    if (downloadedUrls.add(absoluteUrl)) {
                        final String url = absoluteUrl;
                        task.incrementSubmittedSegments();
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "ts", extractFilenameFromUrl(url));
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(filename);
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("下载 TS 分片失败 [{}]: {}", url, e.getMessage());
                            } finally {
                                task.incrementDownloadedSegments();
                                task.decrementSubmittedSegments();
                            }
                        });
                        newCount++;
                    }
                }

                // 等待本次分片全部下载完成
                int downloaded = 0;
                while (downloaded < newCount) {
                    String filename = resultQueue.poll(5, TimeUnit.SECONDS);
                    if (filename != null) {
                        pendingFiles.add(filename);
                        downloaded++;
                    } else if (newCount == 0) {
                        break;
                    }
                }

                // 本次分片下载完成后，二进制追加到 final.ts
                if (!pendingFiles.isEmpty()) {
                    pendingFiles.sort(String::compareTo);
                    appendBinary(finalTs, pendingFiles, tmpDir);
                    pendingFiles.clear();
                }

                // 轮询间隔
                long elapsed = System.currentTimeMillis() - loopStartTime;
                Thread.sleep(Math.max(100, pollIntervalMs - elapsed));

            } catch (Exception e) {
                log.error("TS 下载循环异常: {}", e.getMessage(), e);
                if (!task.isStopped()) Thread.sleep(2000);
            }
        }

        downloadExecutor.shutdown();
        downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);

        log.info("TS 录制结束，共 {} bytes，正在转 MP4...", Files.size(finalTs));

        // 录制结束：TS 转 MP4
        Path finalMp4 = finalTs.resolveSibling(finalTs.getFileName().toString().replace(".ts", ".mp4"));
        ProcessBuilder pb = new ProcessBuilder(shellCmd(String.format(
                "\"%s\" -y -i \"%s\" -c copy \"%s\"",
                getFfmpegPath(), finalTs.toAbsolutePath(), finalMp4.toAbsolutePath())));
        int exit = pb.start().waitFor();

        if (exit == 0) {
            task.setFinalOutputFile(finalMp4);
            log.info("TS 转 MP4 完成: {}", finalMp4.getFileName());
            // 删除临时 TS 文件
            Files.deleteIfExists(finalTs);
        } else {
            log.error("TS 转 MP4 失败，保留 TS 文件: {}", finalTs);
            task.setFinalOutputFile(finalTs);
        }

        cleanupTempFiles(task);
    }

    /**
     * 二进制追加 TS 分片到最终文件
     */
    private void appendBinary(Path finalFile, List<String> filenames, Path tmpDir) throws Exception {
        OpenOption[] opts = Files.exists(finalFile)
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};


        try (OutputStream out = Files.newOutputStream(finalFile, opts)) {
            for (String filename : filenames) {
                Path segment = tmpDir.resolve(filename);
                Files.copy(segment, out);
                Files.deleteIfExists(segment); // 下载完立即删除
            }
        }
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
        Path tmpDir = task.getTmpDir();

        // 1. 二进制拼接视频 → video_partN.mp4
        Path videoPartFile = tmpDir.resolve("video_part" + (partIndex + 1) + ".mp4");
        concatenateM4sSegments(videoPartFile, videoFiles, tmpDir,
                task.getInitVideoSegmentFile(), "视频");

        // 2a. 无音频：直接作为最终 part
        if (audioFiles.isEmpty()) {
            Files.move(videoPartFile, partFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("无音频，视频直接作为 part: {}", partFile);
        }
        // 2b. 有音频：拼接音频 → 混合音视频
        else {
            Path audioPartFile = tmpDir.resolve("audio_part" + (partIndex + 1) + ".mp4");
            concatenateM4sSegments(audioPartFile, audioFiles, tmpDir,
                    task.getInitAudioSegmentFile(), "音频");
            mergeAvTracks(videoPartFile, audioPartFile, partFile, task);
        }

        // 3. 清理已合并的分片文件
        for (String filename : videoFiles) Files.deleteIfExists(tmpDir.resolve(filename));
        for (String filename : audioFiles) Files.deleteIfExists(tmpDir.resolve(filename));
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
        Path targetFinal = task.getFinalOutputFile();
        if (targetFinal == null) {
            log.error("未设置最终文件路径");
            return;
        }
        if (!Files.exists(partFile)) {
            log.error("appendToFinalFile: part 文件不存在: {}", partFile);
            return;
        }
        log.info("[appendToFinalFile] target={} exists={} part={} partExists={}",
                targetFinal, Files.exists(targetFinal), partFile.getFileName(), Files.exists(partFile));

        try {
            Files.createDirectories(targetFinal.getParent());

            if (!Files.exists(targetFinal)) {
                // 第一个 part：直接重命名
                Files.move(partFile, targetFinal, StandardCopyOption.REPLACE_EXISTING);
                log.info("[appendToFinalFile] 创建最终文件: {} (size={})",
                        targetFinal.getFileName(), Files.size(targetFinal));
            } else {
                // 追加：ffmpeg concat
                Path concatList = Files.createTempFile("concat_", ".txt");
                try (BufferedWriter writer = Files.newBufferedWriter(concatList, StandardOpenOption.CREATE)) {
                    // Windows 路径需要双反斜杠或正斜杠
                    String targetPath = targetFinal.toAbsolutePath().toString().replace("\\", "/");
                    String partPath = partFile.toAbsolutePath().toString().replace("\\", "/");
                    writer.write("file '" + targetPath + "'");
                    writer.newLine();
                    writer.write("file '" + partPath + "'");
                    writer.newLine();
                }

                Path tempOutput = new File(targetFinal.toAbsolutePath()+".tmp.mp4").toPath();
                log.info("临时文件：{}", tempOutput);
                //[appendToFinalFile] 执行追加 concat (target=2026-06-03-22-47-24.mp4, part=part111.mp4)
                log.info("[appendToFinalFile] 执行追加 concat (target={}, part={})",
                        targetFinal.getFileName(), partFile.getFileName());
                ProcessBuilder pb = new ProcessBuilder(shellCmd(String.format(
                        "\"%s\" -y -nostdin -f concat -safe 0 -i \"%s\" -c copy \"%s\" 2>&1",
                        getFfmpegPath(), concatList.toAbsolutePath(), tempOutput.toAbsolutePath())));
                pb.redirectErrorStream(false);
                Process process = pb.start();
                String stderr = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    //[appendToFinalFile] concat 成功，tempOutput size=112570827
                    log.info("[appendToFinalFile] concat 成功，tempOutput size={}", Files.size(tempOutput));
                    // 先写临时文件，再原子替换
                    Files.deleteIfExists(targetFinal);
                    Files.move(tempOutput, targetFinal);
                    Files.deleteIfExists(partFile);
                    //[appendToFinalFile] Part 已追加: part111.mp4 -> 2026-06-03-22-47-24.mp4 (final size=112570827)
                    log.info("[appendToFinalFile] Part 已追加: {} -> {} (final size={})",
                            partFile.getFileName(), targetFinal.getFileName(), Files.size(targetFinal));
                } else {
                    log.error("[appendToFinalFile] 追加 part 失败, exitCode={}. concat 文件内容:\n{}\nffmpeg stderr:\n{}",
                            exitCode,
                            Files.readString(concatList),
                            stderr.isEmpty() ? "(empty)" : stderr);
                    Files.deleteIfExists(tempOutput);
                }

                Files.deleteIfExists(concatList);
            }
        } catch (Exception e) {
            log.error("[appendToFinalFile] 追加到最终文件失败: {}", e.getMessage(), e);
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
            log.info("删除 part 文件: {}", partFile.getFileName());
        }

        log.info("最终合并完成: {}", finalOutput);
    }

    // -------------------- Init 片段处理 --------------------

    private void downloadInitSegments(RecordingTask task, String videoChunklistUrl,
                                        String audioChunklistUrl, Path tmpDir) {
        try {
            String videoChunklistContent = httpGet(videoChunklistUrl);
            log.info("视频 chunklist 内容前 500 字符:\n{}",
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
                    log.info("输出目录不为空，保留: {} (内含: {})", outputDir, listDirectoryContents(outputDir));
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
     * 下载单个片段（使用 Hutool HttpRequest，支持自动重试和更好的 SSL 处理）
     */
    private String downloadSegment(String segmentUrl, Path tmpDir) throws Exception {
        String filename = segmentUrl.substring(segmentUrl.lastIndexOf("/") + 1);
        int queryIndex = filename.indexOf("?");
        if (queryIndex > 0) {
            filename = filename.substring(0, queryIndex);
        }
        Path outputPath = tmpDir.resolve(filename);

        if (Files.exists(outputPath)) {
            log.info("片段已存在，跳过: {}", filename);
            return filename;
        }

        HttpResponse response;
        try {
            response = HttpRequest.get(segmentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
                    .timeout(30000)
                    .execute();
        } catch (cn.hutool.core.io.IORuntimeException e) {
            // SSL 握手失败 = CDN 会话已旋转，该 URL 永久失效，立即刷新 chunklist
            Throwable cause = e.getCause();
            if (cause instanceof javax.net.ssl.SSLException
                    || cause instanceof java.io.EOFException) {
                throw new UrlExpiredException("SSL session rotated: " + segmentUrl, segmentUrl, -1);
            }
            throw e;
        }

        int status = response.getStatus();
        if (status != 200) {
            throw new IOException("HTTP " + status + ": " + segmentUrl);
        }

        try (InputStream in = response.bodyStream();
             OutputStream out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        log.info("下载完成: {}", filename);
        return filename;
    }

    /**
     * HTTP GET 请求（使用 Hutool）
     * 当返回 403 时抛出 UrlExpiredException
     */
    private String httpGet(String urlStr) {
        try {
            HttpResponse response = HttpRequest.get(urlStr)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
                    .timeout(30000)
                    .execute();

            int statusCode = response.getStatus();

            if (statusCode == 403) {
                throw new UrlExpiredException("URL 返回 403，Token 可能已过期: " + urlStr, urlStr, 403);
            }
            if (statusCode != 200) {
                throw new UrlExpiredException("http请求失败，状态码：" + statusCode, urlStr, statusCode);
            }

            return response.body();
        } catch (UrlExpiredException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP GET failed: " + urlStr, e);
        }
    }

    /**
     * LL-HLS chunklist 专用 GET
     * 首次请求不带 _HLS_msn/_HLS_part 参数
     * 后续请求自动追加 _HLS_msn=X&_HLS_part=Y 参数
     *
     * @param url    chunklist URL
     * @param task   录制任务（含序列号状态）
     * @param isAudio true=音频流，false=视频流
     * @return chunklist 内容
     */
    private String httpGetForLlHlsChunklist(String url, RecordingTask task, boolean isAudio) {
        String fullUrl;
        if (task.isFirstChunklistFetched()) {
            // 后续请求：追加 LL-HLS 序列号参数
            long msn = isAudio ? task.getAudioNextMsn() : task.getVideoNextMsn();
            int part = isAudio ? task.getAudioNextPart() : task.getVideoNextPart();
            fullUrl = appendLlHlsParams(url, msn, part);
            log.info("LL-HLS 增量请求 [{}]: msn={}, part={}", isAudio ? "audio" : "video", msn, part);
        } else {
            // 首次请求：不带参数，获取完整 chunklist
            fullUrl = url;
            log.info("LL-HLS 首次请求（完整 chunklist）");
        }
        return httpGet(fullUrl);
    }

    /**
     * 向 chunklist URL 追加 _HLS_msn 和 _HLS_part 参数
     */
    private String appendLlHlsParams(String url, long msn, int part) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "_HLS_msn=" + msn + "&_HLS_part=" + part;
    }

    /**
     * 从分片文件名提取媒体序列号（msn）
     * 文件名格式: seg_<res>_<msn>_<type>_<streamId>_llhls.m4s
     * 例如: seg_4_2574_video_5836966573701032432_llhls.m4s → msn=2574
     */
    private long extractMsnFromFilename(String filename) {
        // seg_4_2574_video_... → 分隔符为下划线，第3段为msn
        String name = Paths.get(filename).getFileName().toString();
        String[] parts = name.split("_");
        if (parts.length >= 3) {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * 根据 chunklist 响应更新 LL-HLS 序列号
     * 解析 EXT-X-MEDIA-SEQUENCE 和片段数量，推算下一次应请求的 msn/part
     *
     * @param content    chunklist 内容
     * @param task       录制任务
     * @param isAudio    true=音频流
     */
    private void updateLlHlsSequence(String content, RecordingTask task, boolean isAudio) {
        try {
            Map<String, Object> parseResult = HlsParser.parseChunklistWithInit(content, null);
            HlsParser.LlHlsChunklistInfo info = (HlsParser.LlHlsChunklistInfo) parseResult.get("llHlsInfo");

            if (isAudio) {
                task.setAudioNextMsn(info.nextMsn);
                task.setAudioNextPart(info.nextPart);
            } else {
                task.setVideoNextMsn(info.nextMsn);
                task.setVideoNextPart(info.nextPart);
            }

            log.info("LL-HLS 序列更新 [{}]: msn={}, part={}, segCount={}, partCount={}",
                    isAudio ? "audio" : "video",
                    info.nextMsn, info.nextPart,
                    info.segmentCount, info.partCount);
        } catch (Exception e) {
            log.warn("解析 LL-HLS 序列信息失败（不影响录制）: {}", e.getMessage());
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

    /**
     * 从 URL 中安全提取文件名
     * 正确处理 https:// 前缀、URL 编码、query string
     * 示例: https://edge3-nrt.live.mmcdn.com/.../seg_5_1714_video_...m4s?session=xxx → seg_5_1714_video_...m4s
     */
    private String extractFilenameFromUrl(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            String path = parsed.getPath();
            path = java.net.URLDecoder.decode(path, "UTF-8");
            int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int queryIdx = filename.indexOf('?');
            return queryIdx > 0 ? filename.substring(0, queryIdx) : filename;
        } catch (Exception e) {
            // fallback: 找最后一个斜杠后面的部分
            int lastSlash = url.lastIndexOf('/');
            String filename = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
            int queryIdx = filename.indexOf('?');
            return queryIdx > 0 ? filename.substring(0, queryIdx) : filename;
        }
    }

    // -------------------- LL-HLS 下载辅助 --------------------

    /**
     * 拉取并解析 LL-HLS chunklist，返回视频/音频分片列表及轮询间隔
     */
    private record LlHlsChunklists(
            List<HlsParser.SegmentInfo> videoSegments,
            List<HlsParser.SegmentInfo> audioSegments,
            long pollIntervalMs
    ) {}

    private LlHlsChunklists fetchChunklistsForM4s(RecordingTask task,
                                                   String videoChunklistUrl,
                                                   String audioChunklistUrl) throws Exception {
        // 视频 chunklist
        String videoContent = httpGetForLlHlsChunklist(videoChunklistUrl, task, false);
        HlsParser.LlHlsChunklistInfo prevVideoInfo = null;
        if (task.isFirstChunklistFetched()) {
            prevVideoInfo = new HlsParser.LlHlsChunklistInfo();
            prevVideoInfo.nextMsn = task.getVideoNextMsn();
            prevVideoInfo.nextPart = task.getVideoNextPart();
        }
        Map<String, Object> videoResult = HlsParser.parseChunklistWithInit(videoContent, prevVideoInfo);
        updateLlHlsSequence(videoContent, task, false);
        if (!task.isFirstChunklistFetched()) task.setFirstChunklistFetched(true);

        @SuppressWarnings("unchecked")
        List<HlsParser.SegmentInfo> videoSegments =
                (List<HlsParser.SegmentInfo>) videoResult.get("segments");

        Long partHoldBack = (Long) videoResult.get("partHoldBack");
        long pollIntervalMs = 2000;
        if (partHoldBack != null && partHoldBack > 0) {
            pollIntervalMs = Math.max(500, (long) (partHoldBack * 1000) - 500);
        }

        // 音频 chunklist
        List<HlsParser.SegmentInfo> audioSegments = new ArrayList<>();
        if (audioChunklistUrl != null) {
            String audioContent = httpGetForLlHlsChunklist(audioChunklistUrl, task, true);
            Map<String, Object> audioResult = HlsParser.parseChunklistWithInit(audioContent, null);
            audioSegments = (List<HlsParser.SegmentInfo>) audioResult.get("segments");
            updateLlHlsSequence(audioContent, task, true);
        }

        return new LlHlsChunklists(videoSegments, audioSegments, pollIntervalMs);
    }

    private int submitVideoM4sDownloads(List<HlsParser.SegmentInfo> segments,
                                        String videoChunklistUrl,
                                        RecordingTask task,
                                        Path tmpDir,
                                        Set<String> downloadedVideoUrls,
                                        ExecutorService downloadExecutor,
                                        BlockingQueue<DownloadResult> resultQueue) {
        int submitted = 0;
        for (HlsParser.SegmentInfo seg : segments) {
            String url = toAbsoluteUrl(videoChunklistUrl, seg.getUrl());
            if (downloadedVideoUrls.add(url)) {
                final String fUrl = url;
                task.incrementSubmittedSegments();
                downloadExecutor.submit(() -> {
                    try {
                        task.addActiveDownload(fUrl, "video", extractFilenameFromUrl(fUrl));
                        String fn = downloadSegment(fUrl, tmpDir);
                        task.removeActiveDownload(fUrl);
                        resultQueue.offer(new DownloadResult(fn, "video", true));
                    } catch (Exception e) {
                        task.removeActiveDownload(fUrl);
                        String em = e.getMessage();
                        // 永久失败：HTTP 404/403（会话失效）或 SSL 异常（CDN 会话已旋转）
                        boolean isPermanent = em != null && (em.contains("404") || em.contains("403") || em.contains("SSL"));
                        downloadedVideoUrls.remove(fUrl);
                        if (isPermanent) {
                            long msn = extractMsnFromFilename(fUrl);
                            getPermanentFailures(task).put(msn, System.currentTimeMillis());
                            resultQueue.offer(new DownloadResult("FAILED_" + msn, "video", false));
                            log.warn("视频片段永久失败 [{}] (msn={})，已加入跳过列表", fUrl, msn);
                        } else {
                            // 非永久失败也通知 Merger，以便跳过机制能生效
                            long msn = extractMsnFromFilename(fUrl);
                            resultQueue.offer(new DownloadResult("FAILED_" + msn, "video", false));
                            log.error("下载视频片段失败 [{}]: {}，已通知 Merger 跳过", fUrl, e.getMessage(), e);
                        }
                    } finally {
                        task.incrementDownloadedSegments();
                        task.decrementSubmittedSegments();
                    }
                });
                submitted++;
            }
        }
        return submitted;
    }

    private int submitAudioM4sDownloads(List<HlsParser.SegmentInfo> segments,
                                        String audioChunklistUrl,
                                        RecordingTask task,
                                        Path tmpDir,
                                        Set<String> downloadedAudioUrls,
                                        ExecutorService downloadExecutor,
                                        BlockingQueue<DownloadResult> resultQueue) {
        int submitted = 0;
        for (HlsParser.SegmentInfo seg : segments) {
            String url = toAbsoluteUrl(audioChunklistUrl, seg.getUrl());
            if (downloadedAudioUrls.add(url)) {
                final String fUrl = url;
                task.incrementSubmittedSegments();
                downloadExecutor.submit(() -> {
                    try {
                        task.addActiveDownload(fUrl, "audio", extractFilenameFromUrl(fUrl));
                        String fn = downloadSegment(fUrl, tmpDir);
                        task.removeActiveDownload(fUrl);
                        resultQueue.offer(new DownloadResult(fn, "audio", true));
                    } catch (Exception e) {
                        task.removeActiveDownload(fUrl);
                        String em = e.getMessage();
                        // 永久失败：HTTP 404/403（会话失效）或 SSL 异常（CDN 会话已旋转）
                        boolean isPermanent = em != null && (em.contains("404") || em.contains("403") || em.contains("SSL"));
                        downloadedAudioUrls.remove(fUrl);
                        if (isPermanent) {
                            long msn = extractMsnFromFilename(fUrl);
                            getPermanentFailures(task).put(msn, System.currentTimeMillis());
                            resultQueue.offer(new DownloadResult("FAILED_" + msn, "audio", false));
                            log.warn("音频片段永久失败 [{}] (msn={})，已加入跳过列表", fUrl, msn);
                        } else {
                            // 非永久失败也通知 Merger，以便跳过机制能生效
                            long msn = extractMsnFromFilename(fUrl);
                            resultQueue.offer(new DownloadResult("FAILED_" + msn, "audio", false));
                            log.error("下载音频片段失败 [{}]: {}，已通知 Merger 跳过", fUrl, e.getMessage(), e);
                        }
                    } finally {
                        task.incrementDownloadedSegments();
                        task.decrementSubmittedSegments();
                    }
                });
                submitted++;
            }
        }
        return submitted;
    
    }

    // -------------------- 分片合并辅助 --------------------

    /**
     * 二进制拼接 M4S 分片（视频或音频），可附带 init 片段
     *
     * @param outputFile 输出文件（含 init+segments）
     * @param segmentFiles 要拼接的片段文件名列表
     * @param tmpDir 临时目录
     * @param initFile init 片段文件（可为空）
     * @param logPrefix "视频" 或 "音频"，用于日志
     */
    private void concatenateM4sSegments(Path outputFile,
                                        List<String> segmentFiles,
                                        Path tmpDir,
                                        Path initFile,
                                        String logPrefix) throws Exception {
        try (OutputStream out = Files.newOutputStream(outputFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (initFile != null && Files.exists(initFile)) {
                Files.copy(initFile, out);
                log.info("写入{} init: {}", logPrefix, initFile.getFileName());
            }
            int written = 0;
            for (String filename : segmentFiles) {
                Path filePath = tmpDir.resolve(filename);
                if (Files.exists(filePath)) {
                    Files.copy(filePath, out);
                    written++;
                } else {
                    log.warn("{}分片不存在: {}", logPrefix, filename);
                }
            }
            log.info("二进制拼接{}: {} 个分片 -> {}", logPrefix, written, outputFile.getFileName());
        }
    }

    /**
     * 将视频 part 文件与音频 part 文件混合为最终 MP4
     */
    private void mergeAvTracks(Path videoPartFile,
                                Path audioPartFile,
                                Path partFile,
                                RecordingTask task) throws Exception {
        String[] cmd = {
                getFfmpegPath(), "-loglevel", "info", "-y",
                "-i", videoPartFile.toAbsolutePath().toString(),
                "-i", audioPartFile.toAbsolutePath().toString(),
                "-c", "copy", "-map", "0:v:0", "-map", "1:a:0",
                partFile.toAbsolutePath().toString()
        };
        log.info("混合音视频 -> {}", partFile);
        runFfmpeg(cmd, task);
        Files.deleteIfExists(videoPartFile);
        Files.deleteIfExists(audioPartFile);
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
                        log.info("[ffmpeg] {}", line);
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
