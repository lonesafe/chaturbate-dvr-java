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

        // CopyOnWriteArrayList：读多写少，merger 线程遍历时无需同步
        List<String> pendingVideoFiles = new CopyOnWriteArrayList<>();
        List<String> pendingAudioFiles = new CopyOnWriteArrayList<>();
        // 按 msn 配对：msn → 视频文件名
        Map<Long, String> videoByMsn = new ConcurrentHashMap<>();
        // 按 msn 配对：msn → 音频文件名
        Map<Long, String> audioByMsn = new ConcurrentHashMap<>();

        BlockingQueue<DownloadResult> resultQueue = new LinkedBlockingQueue<>();
        long pollIntervalMs = 2000;

        ExecutorService downloadExecutor = Executors.newCachedThreadPool();
        final long basePollInterval = pollIntervalMs;

        // 下载 init 片段
        downloadInitSegments(task, videoChunklistUrl, audioChunklistUrl, tmpDir);

        // ── Merger 线程：消费 resultQueue，等待配对，按 msn 顺序合并 ──────────────
        // 策略：
        // - 用 nextExpectedMsn 追踪下一个应合并的序列号
        // - 只在 msn == nextExpectedMsn 时配对合并（保证顺序）
        // - 乱序到达的 segment 留在 pending map，等前面的补齐后再处理
        // - 录制结束时 flush 所有 pending
        Thread mergerThread = new Thread(() -> {
            long nextExpectedMsn = -1;
            int lastLogged = 0;
            int flushCounter = 0;
            // 记录上一次合并成功的时间，用于检测 nextExpectedMsn 是否卡住
            long lastMergeTimestamp = 0;

            while (!task.isStopped() || !resultQueue.isEmpty()) {
                try {
                    DownloadResult result = resultQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (result == null) {
                        // 检测 nextExpectedMsn 是否永久卡住（前方有缺失的分片永久不可达）
                        // 只有 nextExpectedMsn 既不在 map 里、也不在下载中（downloadedUrls 里）才跳过
                        // 且至少等待 3 倍轮询间隔（避免 Merger 刚提交下载就误判）
                        long now = System.currentTimeMillis();
                        if (nextExpectedMsn >= 0 && !videoByMsn.isEmpty()
                                && (now - lastMergeTimestamp) > basePollInterval * 3) {
                            long minMsn = Collections.min(videoByMsn.keySet());
                            long chkMsn = nextExpectedMsn;
                            boolean videoPending = false;
                            for (String f : pendingVideoFiles) { if (extractMsnFromFilename(f) == chkMsn) { videoPending = true; break; } }
                            boolean audioPending = false;
                            for (String f : pendingAudioFiles) { if (extractMsnFromFilename(f) == chkMsn) { audioPending = true; break; } }
                            if (nextExpectedMsn < minMsn && !videoPending && !audioPending) {
                                long gap = minMsn - nextExpectedMsn;
                                if (gap > 20) {
                                    // 大间隙（>20）：通常是 chunklist 刷新后 msn 大幅跳变，
                                    // 前方 chunks 从未被下载（因 token 失效/403 等），直接跳过并日志说明
                                    log.warn("Merger 检测到大间隙 msn {}->{}（共 {} 个），"
                                            + "前方 chunks 未下载（chunklist 刷新或 403），跳过",
                                            nextExpectedMsn, minMsn, gap);
                                } else {
                                    log.warn("Merger msn={} 永久缺失，强制跳过到 msn={}",
                                            nextExpectedMsn, minMsn);
                                }
                                audioByMsn.remove(nextExpectedMsn);
                                Iterator<String> it = pendingAudioFiles.iterator();
                                while (it.hasNext()) {
                                    if (extractMsnFromFilename(it.next()) == nextExpectedMsn) {
                                        it.remove();
                                    }
                                }
                                nextExpectedMsn = minMsn;
                            }
                        }

                        // 队列空：只合 1 个，严格按顺序
                        if (nextExpectedMsn >= 0 && videoByMsn.containsKey(nextExpectedMsn)
                                && audioByMsn.containsKey(nextExpectedMsn)) {
                            // 跳过已过期的 segment（之前被批量 merge 处理过）
                            String videoFile = videoByMsn.get(nextExpectedMsn);
                            Path videoPath = Paths.get(tmpDir.toString(), videoFile);
                            if (!Files.exists(videoPath)) {
                                log.warn("Merger msn={} 文件已不存在（过期），跳过", nextExpectedMsn);
                                audioByMsn.remove(nextExpectedMsn);
                                nextExpectedMsn++;
                            } else {
                                mergeOnePart(task, videoByMsn, audioByMsn,
                                        pendingVideoFiles, pendingAudioFiles, nextExpectedMsn,
                                        videoChunklistUrl, audioChunklistUrl, tmpDir);
                                nextExpectedMsn++;
                                lastMergeTimestamp = System.currentTimeMillis();
                            }
                        }
                        // 流结束：flush 剩余 unpaired segments
                        if (task.isStopped() && resultQueue.isEmpty()) {
                            flushRemainingParts(task, videoByMsn, audioByMsn,
                                    pendingVideoFiles, pendingAudioFiles,
                                    videoChunklistUrl, audioChunklistUrl, tmpDir);
                        }
                        continue;
                    }

                    // 加入 pending 并尝试配对
                    if (!result.success) {
                        long failedMsn = extractMsnFromFilename(result.filename);
                        log.warn("Merger 跳过失败分片 msn={}（{}）", failedMsn, result.type);
                        // 清理 pending 中可能的残留
                        if ("video".equals(result.type)) {
                            videoByMsn.remove(failedMsn);
                            pendingVideoFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
                        } else {
                            audioByMsn.remove(failedMsn);
                            pendingAudioFiles.removeIf(f -> extractMsnFromFilename(f) == failedMsn);
                        }
                        continue;
                    }
                    long msn = extractMsnFromFilename(result.filename);
                    if (msn < 0) {
                        log.warn("Merger 无法从文件名提取 msn: {}", result.filename);
                        continue;
                    }
                    if ("video".equals(result.type)) {
                        pendingVideoFiles.add(result.filename);
                        videoByMsn.put(msn, result.filename);
                    } else {
                        pendingAudioFiles.add(result.filename);
                        audioByMsn.put(msn, result.filename);
                    }

                    // 初始化 nextExpectedMsn
                    if (nextExpectedMsn < 0) {
                        if (!videoByMsn.isEmpty()) {
                            nextExpectedMsn = Collections.min(videoByMsn.keySet());
                        }
                    }

                    // 尝试从 nextExpectedMsn 开始向前推进合并
                    boolean madeProgress;
                    do {
                        madeProgress = false;
                        while (videoByMsn.containsKey(nextExpectedMsn)
                                && audioByMsn.containsKey(nextExpectedMsn)) {
                            mergeOnePart(task, videoByMsn, audioByMsn,
                                    pendingVideoFiles, pendingAudioFiles, nextExpectedMsn,
                                    videoChunklistUrl, audioChunklistUrl, tmpDir);
                            nextExpectedMsn++;
                            madeProgress = true;
                            lastMergeTimestamp = System.currentTimeMillis();
                        }
                        // 也允许 nextExpectedMsn+1（当前一个分片永久丢失时跳过）
                        // 但必须确认 nextExpectedMsn 真的不在两个 map 里（还在下载中的 segment 不跳过）
                        if (!madeProgress && nextExpectedMsn >= 0
                                && !videoByMsn.containsKey(nextExpectedMsn)
                                && !audioByMsn.containsKey(nextExpectedMsn)) {
                            long skipMsn = nextExpectedMsn + 1;
                            if (videoByMsn.containsKey(skipMsn) && audioByMsn.containsKey(skipMsn)
                                    && Files.exists(task.getTmpDir().resolve(videoByMsn.get(skipMsn)))
                                    && Files.exists(task.getTmpDir().resolve(audioByMsn.get(skipMsn)))) {
                                log.warn("Merger 跳过 msn={}（缺少 msn={}，文件可能永久丢失）",
                                        skipMsn, nextExpectedMsn);
                                mergeOnePart(task, videoByMsn, audioByMsn,
                                        pendingVideoFiles, pendingAudioFiles, skipMsn,
                                        videoChunklistUrl, audioChunklistUrl, tmpDir);
                                nextExpectedMsn = skipMsn + 1;
                                madeProgress = true;
                                lastMergeTimestamp = System.currentTimeMillis();
                            }
                        }
                    } while (madeProgress);

                    // 日志进度
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
        }, "Merger-" + task.getUsername());
        mergerThread.start();

        // ── 主循环：轮询 chunklist，提交下载（不等待） ───────────────────────
        while (!task.isStopped()) {
            try {
                // 1. 获取视频 chunklist（LL-HLS 增量请求）
                String videoChunklistContent = httpGetForLlHlsChunklist(videoChunklistUrl, task, false);
                HlsParser.LlHlsChunklistInfo prevVideoInfo = null;
                if (task.isFirstChunklistFetched()) {
                    prevVideoInfo = new HlsParser.LlHlsChunklistInfo();
                    prevVideoInfo.nextMsn = task.getVideoNextMsn();
                    prevVideoInfo.nextPart = task.getVideoNextPart();
                }
                Map<String, Object> videoParseResult = HlsParser.parseChunklistWithInit(videoChunklistContent, prevVideoInfo);
                updateLlHlsSequence(videoChunklistContent, task, false);
                if (!task.isFirstChunklistFetched()) {
                    task.setFirstChunklistFetched(true);
                }

                Long partHoldBack = (Long) videoParseResult.get("partHoldBack");
                if (partHoldBack != null && partHoldBack > 0) {
                    pollIntervalMs = Math.max(500, (long) (partHoldBack * 1000) - 500);
                }

                @SuppressWarnings("unchecked")
                List<HlsParser.SegmentInfo> videoSegments =
                        (List<HlsParser.SegmentInfo>) videoParseResult.get("segments");

                List<HlsParser.SegmentInfo> audioSegments = new ArrayList<>();
                if (audioChunklistUrl != null) {
                    String audioChunklistContent = httpGetForLlHlsChunklist(audioChunklistUrl, task, true);
                    Map<String, Object> audioParseResult = HlsParser.parseChunklistWithInit(audioChunklistContent, null);
                    audioSegments = (List<HlsParser.SegmentInfo>) audioParseResult.get("segments");
                    updateLlHlsSequence(audioChunklistContent, task, true);
                }

                if (videoSegments.isEmpty()) {
                    log.warn("视频 chunklist 为空，直播可能已结束");
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                // 2. 提交异步下载（fire-and-forget，主循环不等待）
                int newVideo = 0, newAudio = 0;
                for (HlsParser.SegmentInfo seg : videoSegments) {
                    String abs = toAbsoluteUrl(videoChunklistUrl, seg.getUrl());
                    if (downloadedVideoUrls.add(abs)) {
                        final String url = abs;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "video", Paths.get(url).getFileName().toString());
                                String fn = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(fn, "video", true));
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                // 403 = session 过期，忽略（保留 downloaded 标记，下次 chunklist 刷新 URL 后会重试新 segment）
                                if (e.getMessage() != null && e.getMessage().contains("403")) {
                                    downloadedVideoUrls.remove(url);
                                    long failedMsn = extractMsnFromFilename(url);
                                    resultQueue.offer(new DownloadResult("FAILED_" + failedMsn, "video", false));
                                    log.warn("视频片段 403 丢弃 [{}]，已下载chunk将立即合并", url);
                                } else {
                                    downloadedVideoUrls.remove(url);
                                    log.error("下载视频片段失败 [{}]: {}，已标记可重试", url, e.getMessage());
                                }
                            }
                        });
                        newVideo++;
                    }
                }

                for (HlsParser.SegmentInfo seg : audioSegments) {
                    String abs = toAbsoluteUrl(audioChunklistUrl, seg.getUrl());
                    if (downloadedAudioUrls.add(abs)) {
                        final String url = abs;
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "audio", Paths.get(url).getFileName().toString());
                                String fn = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(new DownloadResult(fn, "audio", true));
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                // 403 = session 过期，忽略（保留 downloaded 标记，下次 chunklist 刷新 URL 后会重试新 segment）
                                if (e.getMessage() != null && e.getMessage().contains("403")) {
                                    downloadedAudioUrls.remove(url);
                                    long failedMsn = extractMsnFromFilename(url);
                                    resultQueue.offer(new DownloadResult("FAILED_" + failedMsn, "audio", false));
                                    log.warn("音频片段 403 丢弃 [{}]，已下载chunk将立即合并", url);
                                } else {
                                    downloadedAudioUrls.remove(url);
                                    log.error("下载音频片段失败 [{}]: {}，已标记可重试", url, e.getMessage());
                                }
                            }
                        });
                        newAudio++;
                    }
                }

                if (newVideo > 0 || newAudio > 0) {
                    log.info("提交下载: {} 视频, {} 音频 (Merger 待处理: {})",
                            newVideo, newAudio, pendingVideoFiles.size() + pendingAudioFiles.size());
                }

                // 3. 轮询间隔（完全独立于下载速度）
                Thread.sleep(pollIntervalMs);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.error("Chunklist 轮询异常: {}，{}ms 后重试", e.getMessage(), pollIntervalMs, e);
                Thread.sleep(pollIntervalMs);
            }
        }

        // ── 退出：等待 Merger 处理完队列剩余内容 ──────────────────────────────
        try {
            mergerThread.interrupt();
            mergerThread.join(5000);
        } catch (InterruptedException e) {
            log.warn("Merger 线程等待被中断");
        }

        downloadExecutor.shutdown();
        try {
            downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("下载线程池关闭被中断");
        }

        Path finalOutput = task.getFinalOutputFile();
        if (finalOutput != null && Files.exists(finalOutput)) {
            log.info("录制完成: {} ({} bytes)", finalOutput.getFileName(), Files.size(finalOutput));
        } else {
            log.warn("录制结束但未生成最终文件");
        }

        cleanupTempFiles(task);
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
                        downloadExecutor.submit(() -> {
                            try {
                                task.addActiveDownload(url, "ts", Paths.get(url).getFileName().toString());
                                String filename = downloadSegment(url, tmpDir);
                                task.removeActiveDownload(url);
                                resultQueue.offer(filename);
                            } catch (Exception e) {
                                task.removeActiveDownload(url);
                                log.error("下载 TS 分片失败 [{}]: {}", url, e.getMessage());
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
        String cmd = String.format("%s -y -i \"%s\" -c copy \"%s\"",
                getFfmpegPath(), finalTs.toString(), finalMp4.toString());
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
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
        boolean hasAudio = !audioFiles.isEmpty();

        // 1. 二进制拼接视频分片
        Path videoPartFile = task.getTmpDir().resolve("video_part" + (partIndex + 1) + ".mp4");
        try (OutputStream out = Files.newOutputStream(videoPartFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (task.getInitVideoSegmentFile() != null && Files.exists(task.getInitVideoSegmentFile())) {
                Files.copy(task.getInitVideoSegmentFile(), out);
                log.info("写入视频 init: {}", task.getInitVideoSegmentFile().getFileName());
            }
            for (String filename : videoFiles) {
                Path filePath = task.getTmpDir().resolve(filename);
                if (Files.exists(filePath)) {
                    Files.copy(filePath, out);
                    log.info("写入视频分片: {}", filename);
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
                    log.info("写入音频 init: {}", task.getInitAudioSegmentFile().getFileName());
                }
                for (String filename : audioFiles) {
                    Path filePath = task.getTmpDir().resolve(filename);
                    if (Files.exists(filePath)) {
                        Files.copy(filePath, audioOut);
                        log.info("写入音频分片: {}", filename);
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
                    writer.write("file '" + targetFinal.toAbsolutePath() + "'");
                    writer.newLine();
                    writer.write("file '" + partFile.toAbsolutePath() + "'");
                    writer.newLine();
                }

                Path tempOutput = Files.createTempFile("temp_merge_", ".mp4");
                String concatCmd = String.format("%s -y -f concat -safe 0 -i \"%s\" -c copy \"%s\"",
                        getFfmpegPath(), concatList.toString(), tempOutput.toString());
                //[appendToFinalFile] 执行追加 concat (target=2026-06-03-22-47-24.mp4, part=part111.mp4)
                log.info("[appendToFinalFile] 执行追加 concat (target={}, part={})",
                        targetFinal.getFileName(), partFile.getFileName());
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", concatCmd);
                int exitCode = pb.start().waitFor();

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
                    log.error("[appendToFinalFile] 追加 part 失败, exitCode={}", exitCode);
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
            log.info("片段已存在，跳过: {}", filename);
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

        log.info("下载完成: {}", filename);
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
