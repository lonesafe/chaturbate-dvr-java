package com.chaturbate.dvr.task;

import com.chaturbate.dvr.dto.ActiveDownload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 录制任务
 * 承载单个直播间录制过程中的所有状态数据
 * <p>
 * 注意：此类不持有 HlsRecorder 引用，文件操作依赖外部传入的路径配置
 */
public class RecordingTask {

    private String taskId;
    private String username;
    private String masterM3u8Url;
    private long startTime;
    private String format; // "fmp4" 或 "ts"
    private String chunklistUrl;
    private Path tmpDir;
    private Path outputDir;
    private Path finalOutputFile;
    private Path initVideoSegmentFile;
    private Path initAudioSegmentFile;

    /** 录制根配置路径（用于 cleanup） */
    private final String configuredTmpPath;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private int partCount = 0;
    private final ConcurrentHashMap<String, ActiveDownload> activeDownloads = new ConcurrentHashMap<>();
    private int downloadedSegments = 0;
    private int submittedSegments = 0;
    private int threadPoolActiveCount = 0;
    private int threadPoolQueueSize = 0;
    private volatile ExecutorService downloadExecutor;

    // -------------------- LL-HLS 序列号追踪 --------------------
    /** 视频流下一个应请求的 msn（首次为 0，后续由 chunklist 响应更新） */
    private long videoNextMsn = 0;
    /** 视频流下一个应请求的 part index */
    private int videoNextPart = 0;
    /** 音频流下一个应请求的 msn */
    private long audioNextMsn = 0;
    /** 音频流下一个应请求的 part index */
    private int audioNextPart = 0;
    /** 是否已完成首次 chunklist 拉取（首次不带 _HLS_msn/_HLS_part） */
    private boolean firstChunklistFetched = false;

    /**
     * @param taskId           任务ID
     * @param username         主播用户名
     * @param masterM3u8Url    master.m3u8 地址
     * @param configuredTmpPath 配置的临时目录根路径（用于 cleanup）
     */
    public RecordingTask(String taskId, String username, String masterM3u8Url, String configuredTmpPath) {
        this.taskId = taskId;
        this.username = username;
        this.masterM3u8Url = masterM3u8Url;
        this.configuredTmpPath = configuredTmpPath;
        this.startTime = System.currentTimeMillis();
    }

    // -------------------- 状态查询 --------------------

    public String getTaskId() {
        return taskId;
    }

    public String getUsername() {
        return username;
    }

    public String getMasterM3u8Url() {
        return masterM3u8Url;
    }

    public void setMasterM3u8Url(String masterM3u8Url) {
        this.masterM3u8Url = masterM3u8Url;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getChunklistUrl() {
        return chunklistUrl;
    }

    public void setChunklistUrl(String chunklistUrl) {
        this.chunklistUrl = chunklistUrl;
    }

    public Path getTmpDir() {
        return tmpDir;
    }

    public void setTmpDir(Path tmpDir) {
        this.tmpDir = tmpDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public Path getFinalOutputFile() {
        return finalOutputFile;
    }

    public void setFinalOutputFile(Path finalOutputFile) {
        this.finalOutputFile = finalOutputFile;
    }

    public Path getInitVideoSegmentFile() {
        return initVideoSegmentFile;
    }

    public void setInitVideoSegmentFile(Path initVideoSegmentFile) {
        this.initVideoSegmentFile = initVideoSegmentFile;
    }

    public Path getInitAudioSegmentFile() {
        return initAudioSegmentFile;
    }

    public void setInitAudioSegmentFile(Path initAudioSegmentFile) {
        this.initAudioSegmentFile = initAudioSegmentFile;
    }

    public long getRuntimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    public boolean isStopped() {
        return stopped.get();
    }

    public void stop() {
        stopped.set(true);
    }

    // -------------------- 日志 --------------------

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

    public void setError(String error) {
        addLog("ERROR: " + error);
    }

    // -------------------- 分片计数 --------------------

    public int getPartCount() {
        return partCount;
    }

    public void incrementPartCount() {
        partCount++;
    }

    // -------------------- 活跃下载追踪 --------------------

    public void addActiveDownload(String url, String type, String filename) {
        activeDownloads.put(url, new ActiveDownload(url, type, filename));
    }

    public void removeActiveDownload(String url) {
        activeDownloads.remove(url);
    }

    public List<ActiveDownload> getActiveDownloads() {
        return new ArrayList<>(activeDownloads.values());
    }

    public int getActiveDownloadCount() {
        return activeDownloads.size();
    }

    public int getDownloadedSegments() {
        return downloadedSegments;
    }

    public void incrementDownloadedSegments() {
        downloadedSegments++;
    }

    public int getSubmittedSegments() {
        return submittedSegments;
    }

    public void incrementSubmittedSegments() {
        submittedSegments++;
    }

    public void decrementSubmittedSegments() {
        submittedSegments--;
    }

    public int getThreadPoolActiveCount() {
        return threadPoolActiveCount;
    }

    public void setThreadPoolActiveCount(int count) {
        this.threadPoolActiveCount = count;
    }

    public int getThreadPoolQueueSize() {
        return threadPoolQueueSize;
    }

    public void setThreadPoolQueueSize(int size) {
        this.threadPoolQueueSize = size;
    }

    public void setDownloadExecutor(ExecutorService executor) {
        this.downloadExecutor = executor;
    }

    /**
     * 刷新线程池统计（activeCount, queueSize）
     */
    public void refreshThreadPoolStats() {
        if (downloadExecutor instanceof ThreadPoolExecutor tpe) {
            threadPoolActiveCount = tpe.getActiveCount();
            threadPoolQueueSize = tpe.getQueue().size();
        }
    }

    // -------------------- LL-HLS 序列号 --------------------

    public long getVideoNextMsn() { return videoNextMsn; }
    public void setVideoNextMsn(long msn) { this.videoNextMsn = msn; }
    public int getVideoNextPart() { return videoNextPart; }
    public void setVideoNextPart(int part) { this.videoNextPart = part; }
    public long getAudioNextMsn() { return audioNextMsn; }
    public void setAudioNextMsn(long msn) { this.audioNextMsn = msn; }
    public int getAudioNextPart() { return audioNextPart; }
    public void setAudioNextPart(int part) { this.audioNextPart = part; }
    public boolean isFirstChunklistFetched() { return firstChunklistFetched; }
    public void setFirstChunklistFetched(boolean v) { this.firstChunklistFetched = v; }

    // -------------------- 资源清理 --------------------

    /**
     * 清理录制任务产生的临时文件
     * 删除 tmpDir（任务专属临时目录）
     */
    public void cleanup() {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try {
                Files.walk(tmpDir)
                        .sorted(Comparator.comparing(Path::toString).reversed())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                // ignore
                            }
                        });
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
