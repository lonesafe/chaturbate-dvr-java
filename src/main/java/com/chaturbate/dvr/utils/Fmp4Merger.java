package com.chaturbate.dvr.utils;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * fMP4 合并工具类（基于二进制拼接）
 * 
 * 将 init segment (moov) 和多个 media segments (moof+mdat)
 * 合并为一个可播放的 MP4 文件
 * 
 * 原理：直接拼接二进制数据，init + segments 顺序写入
 */
public class Fmp4Merger {

    /**
     * 合并 fMP4 片段（二进制拼接方式）
     * 
     * @param initFile init segment 文件（包含 moov box）
     * @param segmentFiles media segment 文件列表（每个包含 moof+mdat）
     * @param outputFile 输出文件
     * @throws IOException 如果读写失败
     */
    public static void merge(Path initFile, List<Path> segmentFiles, Path outputFile) throws IOException {
        // 空检查
        if (initFile == null || !Files.exists(initFile) || Files.size(initFile) == 0) {
            throw new IOException("Init file is null, missing, or empty: " + initFile);
        }
        if (segmentFiles == null || segmentFiles.isEmpty()) {
            throw new IOException("Segment files list is null or empty");
        }
        
        // 验证 init 文件包含 moov
        if (!hasMoov(initFile)) {
            throw new IOException("Init file does not contain moov box: " + initFile);
        }
        
        // 二进制拼接：init + 所有 segments
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            // 1. 写入 init 文件（包含 ftyp + moov）
            Files.copy(initFile, out);
            
            // 2. 逐个写入 segment 文件（包含 moof + mdat）
            for (Path segPath : segmentFiles) {
                if (!Files.exists(segPath) || Files.size(segPath) == 0) {
                    throw new IOException("Segment file is null, missing, or empty: " + segPath);
                }
                Files.copy(segPath, out);
            }
        }
    }
    
    /**
     * 检查文件是否包含 moov box
     */
    private static boolean hasMoov(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = new byte[8];
            int read = in.read(header);
            if (read < 8) return false;
            // 读取 box size 和 type
            long size = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) 
                      | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            String type = new String(header, 4, 4, "ISO-8859-1");
            return "moov".equals(type);
        }
    }
    
    /**
     * 合并视频和音频（分别合并后再用 ffmpeg 混合）
     */
    public static void mergeVideoAudio(Path videoInit, List<Path> videoSegs, 
                                      Path audioInit, List<Path> audioSegs,
                                      Path outputDir) throws IOException {
        // 1. 合并视频
        Path videoOnly = outputDir.resolve("video_only.mp4");
        merge(videoInit, videoSegs, videoOnly);
        
        // 2. 合并音频
        Path audioOnly = outputDir.resolve("audio_only.mp4");
        merge(audioInit, audioSegs, audioOnly);
        
        // 3. 调用 ffmpeg 混合（由 HlsRecorder 负责）
    }
}