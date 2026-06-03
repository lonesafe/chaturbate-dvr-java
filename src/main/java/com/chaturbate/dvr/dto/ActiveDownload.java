package com.chaturbate.dvr.dto;

/**
 * 活跃下载信息
 * 记录当前正在下载的分片信息，用于前端实时展示
 */
public class ActiveDownload {

    private final String url;
    private final String type; // "video" 或 "audio"
    private final long startTime;
    private final String filename;

    public ActiveDownload(String url, String type, String filename) {
        this.url = url;
        this.type = type;
        this.filename = filename;
        this.startTime = System.currentTimeMillis();
    }

    public String getUrl() {
        return url;
    }

    public String getType() {
        return type;
    }

    public String getFilename() {
        return filename;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
