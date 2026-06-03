package com.chaturbate.dvr.dto;

/**
 * 下载结果
 * 用于在异步下载线程和主录制线程之间传递下载完成信号
 */
public class DownloadResult {

    public final String filename;
    public final String type; // "video" 或 "audio"
    public final boolean success; // false=下载失败（如403），应跳过

    public DownloadResult(String filename, String type) {
        this(filename, type, true);
    }

    public DownloadResult(String filename, String type, boolean success) {
        this.filename = filename;
        this.type = type;
        this.success = success;
    }
}
