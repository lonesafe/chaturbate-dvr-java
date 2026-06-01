package com.chaturbate.dvr.utils;

import org.mp4parser.Container;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.*;
import org.mp4parser.boxes.sampleentry.AudioSampleEntry;
import org.mp4parser.boxes.sampleentry.VisualSampleEntry;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;

/**
 * fMP4 合并工具类（基于 isoparser）
 * 
 * 将 init segment (moov) 和多个 media segments (moof+mdat) 
 * 合并为一个可播放的 MP4 文件
 * 
 * 注意：此实现适用于简单的 fMP4 场景
 */
public class Fmp4Merger {

    /**
     * 合并 fMP4 片段
     * 
     * @param initFile init segment 文件（包含 moov box）
     * @param segmentFiles media segment 文件列表（每个包含 moof+mdat）
     * @param outputFile 输出文件
     * @throws IOException 如果读写失败
     */
    public static void merge(Path initFile, List<Path> segmentFiles, Path outputFile) throws IOException {
        IsoFile initIso = new IsoFile(initFile.toFile());
        
        // 1. 获取 init 中的 moov
        MovieBox moov = initIso.getBoxes(MovieBox.class).get(0);
        
        // 2. 创建输出文件
        try (FileChannel channel = new java.io.FileOutputStream(outputFile.toFile()).getChannel()) {
            
            // 写入 ftyp
            List<FileTypeBox> ftypBoxes = initIso.getBoxes(FileTypeBox.class);
            if (!ftypBoxes.isEmpty()) {
                ftypBoxes.get(0).getBox(channel);
            }
            
            // 写入 moov
            moov.getBox(channel);
            
            // 3. 逐个写入 segment 的 moof + mdat
            for (Path segPath : segmentFiles) {
                IsoFile segIso = new IsoFile(segPath.toFile());
                
                // 写入 moof
                List<MovieFragmentBox> moofBoxes = segIso.getBoxes(MovieFragmentBox.class);
                for (MovieFragmentBox moof : moofBoxes) {
                    moof.getBox(channel);
                }
                
                // 写入 mdat
                List<MediaDataBox> mdatBoxes = segIso.getBoxes(MediaDataBox.class);
                for (MediaDataBox mdat : mdatBoxes) {
                    mdat.getBox(channel);
                }
                
                segIso.close();
            }
        }
        
        initIso.close();
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
        // ffmpeg -i video_only.mp4 -i audio_only.mp4 -c copy output.mp4
    }
}
