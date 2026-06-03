package com.chaturbate.dvr.dto;

/**
 * 下载结果
 * 用于在异步下载线程和主录制线程之间传递下载完成信号
 */
public class DownloadResult {

    public final String filename;
    public final String type; // "video" 或 "audio"

    public DownloadResult(String filename, String type) {
        this.filename = filename;
        this.type = type;
    }
}
