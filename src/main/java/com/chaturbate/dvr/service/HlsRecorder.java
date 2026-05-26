package com.chaturbate.dvr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HLS 直播录制器
 * 使用 ffmpeg 录制 HLS 流（更稳定，支持自动重连）
 */
@Slf4j
@Service
public class HlsRecorder {

    @Value("${dvr.record-path:./recordings}")
    private String recordPath;

    @Value("${dvr.cookie}")
    private String cookie;

    @Value("${dvr.user-agent}")
    private String userAgent;

    @Value("${dvr.preferred-quality:720p}")
    private String preferredQuality;

    @Value("${dvr.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, RecordingTask> activeRecordings = new ConcurrentHashMap<>();

    /**
     * 开始录制
     *
     * @param username   主播用户名
     * @param hlsSource HLS 主播放列表地址
     * @return 录制任务ID
     */
    public String startRecording(String username, String hlsSource) {
        if (activeRecordings.containsKey(username)) {
            log.warn("直播间 [{}] 已经在录制中", username);
            return activeRecordings.get(username).getTaskId();
        }

        String taskId = username + "_" + System.currentTimeMillis();
        RecordingTask task = new RecordingTask(taskId, username, hlsSource);
        activeRecordings.put(username, task);

        executor.submit(() -> {
            try {
                doRecordingWithFfmpeg(task);
            } catch (Exception e) {
                log.error("录制任务 [{}] 异常: {}", taskId, e.getMessage(), e);
                task.setError(e.getMessage());
            } finally {
                activeRecordings.remove(username);
            }
        });

        log.info("开始录制直播间 [{}], 任务ID: {}", username, taskId);
        return taskId;
    }

    /**
     * 停止录制
     *
     * @param username 主播用户名
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
     * 使用 ffmpeg 录制 HLS 流
     */
    private void doRecordingWithFfmpeg(RecordingTask task) throws IOException, InterruptedException {
        Path outputFile = createOutputFile(task.getUsername());
        task.setOutputFile(outputFile);

        String[] command = {
                ffmpegPath,
                "-loglevel", "info",
                "-y",
                "-headers", buildHeaders(),
                "-i", task.getHlsSource(),
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                outputFile.toAbsolutePath().toString()
        };

        log.info("执行 ffmpeg 命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        task.setProcess(process);

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

                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("failed")) {
                    log.warn("[ffmpeg] {}", line);
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode == 0 || task.isStopped()) {
            log.info("录制 [{}] 完成, 文件: {}, 大小: {} bytes",
                    task.getUsername(), outputFile,
                    Files.exists(outputFile) ? Files.size(outputFile) : 0);
        } else {
            throw new RuntimeException("ffmpeg 退出码: " + exitCode);
        }
    }

    private String buildHeaders() {
        return String.format(
                "Cookie: %s\r\nUser-Agent: %s\r\nReferer: https://zh-hans.chaturbate.com/\r\n",
                cookie, userAgent
        );
    }

    private Path createOutputFile(String username) throws IOException {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("%s_%s.mp4", username, dateStr);

        Path dir = Paths.get(recordPath, username);
        Files.createDirectories(dir);

        return dir.resolve(filename);
    }

    /**
     * 录制任务
     */
    public static class RecordingTask {
        private final String taskId;
        private final String username;
        private final String hlsSource;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile Process process;
        private volatile Path outputFile;
        private volatile String error;
        private final LocalDateTime startTime;
        private final List<String> logs = new ArrayList<>();

        public RecordingTask(String taskId, String username, String hlsSource) {
            this.taskId = taskId;
            this.username = username;
            this.hlsSource = hlsSource;
            this.startTime = LocalDateTime.now();
        }

        public void stop() {
            stopped.set(true);
            if (process != null) {
                process.destroy();
            }
        }

        public boolean isStopped() {
            return stopped.get();
        }

        public void setProcess(Process process) {
            this.process = process;
        }

        public void setOutputFile(Path outputFile) {
            this.outputFile = outputFile;
        }

        public void addLog(String logLine) {
            if (logs.size() < 1000) {
                logs.add(logLine);
            }
        }

        public List<String> getLogs() {
            return new ArrayList<>(logs);
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getTaskId() { return taskId; }
        public String getUsername() { return username; }
        public String getHlsSource() { return hlsSource; }
        public Path getOutputFile() { return outputFile; }
        public String getError() { return error; }
        public LocalDateTime getStartTime() { return startTime; }
    }
}
