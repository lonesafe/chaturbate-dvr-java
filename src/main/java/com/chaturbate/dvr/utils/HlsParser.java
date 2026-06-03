package com.chaturbate.dvr.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * HLS M3U8 播放列表解析器
 * 解析主 m3u8 和 chunklist m3u8
 */
@Slf4j
public class HlsParser {

    /**
     * 解析主播放列表 (master.m3u8)
     * 返回所有可用流的信息（包括音频流）
     */
    public static MasterPlaylistInfo parseMasterPlaylist(String m3u8Content) {
        MasterPlaylistInfo result = new MasterPlaylistInfo();
        List<StreamInfo> videoStreams = new ArrayList<>();
        List<AudioStreamInfo> audioStreams = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(m3u8Content))) {
            String line;
            StreamInfo currentStream = null;
            AudioStreamInfo currentAudio = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // 解析音频流
                if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    currentAudio = parseAudioStreamInfo(line);
                    audioStreams.add(currentAudio);
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    currentStream = parseStreamInfo(line);
                } else if (line.length() > 0 && !line.startsWith("#")) {
                    if (currentStream != null) {
                        // 视频流的 chunklist URL
                        currentStream.setChunklistUrl(line);
                        videoStreams.add(currentStream);
                        currentStream = null;
                    }
                }
            }
        } catch (IOException e) {
            log.error("解析主播放列表失败: {}", e.getMessage());
        }

        result.setVideoStreams(videoStreams);
        result.setAudioStreams(audioStreams);
        return result;
    }

    /**
     * 解析音频流信息 (#EXT-X-MEDIA:TYPE=AUDIO,...)
     * 音频 chunklist URL 在 URI="..." 属性中
     */
    private static AudioStreamInfo parseAudioStreamInfo(String line) {
        AudioStreamInfo audio = new AudioStreamInfo();
        String attrs = line.substring(line.indexOf(':') + 1);

        audio.setGroupId(extractAttribute(attrs, "GROUP-ID"));
        audio.setName(extractAttribute(attrs, "NAME"));
        audio.setDefault("YES".equals(extractAttribute(attrs, "DEFAULT")));
        audio.setAutoselect("YES".equals(extractAttribute(attrs, "AUTOSELECT")));
        audio.setChannels(extractAttribute(attrs, "CHANNELS"));
        
        // 关键：从 URI 属性提取音频 chunklist URL
        String uri = extractAttribute(attrs, "URI");
        if (uri != null && !uri.isEmpty()) {
            audio.setChunklistUrl(uri);
            log.debug("解析到音频流 [{}] chunklist: {}", audio.getName(), uri);
        }

        return audio;
    }

    /**
     * 解析片段列表 (chunklist.m3u8)
     * 返回所有片段的 URL
     */
    public static List<SegmentInfo> parseChunklist(String m3u8Content) {
        List<SegmentInfo> segments = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(m3u8Content))) {
            String line;
            double currentDuration = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#EXTINF:")) {
                    // 格式: #EXTINF:10.000,
                    String durationStr = line.substring(line.indexOf(':') + 1).replace(",", "").trim();
                    try {
                        currentDuration = Double.parseDouble(durationStr);
                    } catch (NumberFormatException e) {
                        currentDuration = 0;
                    }
                } else if (line.length() > 0 && !line.startsWith("#")) {
                    // 这是片段 URL (.m4s 或 .ts)
                    SegmentInfo segment = new SegmentInfo();
                    segment.setUrl(line);
                    segment.setDuration(currentDuration);
                    segments.add(segment);
                    currentDuration = 0;
                }
            }
        } catch (IOException e) {
            log.error("解析片段列表失败: {}", e.getMessage());
        }

        return segments;
    }

    /**
     * 解析流信息 (#EXT-X-STREAM-INF:...)
     * 新增：提取 AUDIO 属性（音频流 GROUP-ID）
     */
    private static StreamInfo parseStreamInfo(String line) {
        StreamInfo stream = new StreamInfo();
        String attrs = line.substring(line.indexOf(':') + 1);

        stream.setBandwidth(Integer.parseInt(extractAttribute(attrs, "BANDWIDTH")));
        stream.setResolution(extractAttribute(attrs, "RESOLUTION"));
        stream.setCodecs(extractAttribute(attrs, "CODECS"));
        
        // 关键：提取 AUDIO 属性（音频流 GROUP-ID）
        String audioGroupId = extractAttribute(attrs, "AUDIO");
        stream.setAudioGroupId(audioGroupId);
        
        return stream;
    }

    /**
     * 从属性字符串中提取属性值
     */
    /**
     * 解析片段列表 (chunklist.m3u8)
     * 返回所有片段的 URL 和 init 片段 URL
     * @return 包含 segments, initSegmentUrl, partHoldBack 的 Map
     */
    /**
     * LL-HLS chunklist 解析结果
     */
    public static class LlHlsChunklistInfo {
        /** EXT-X-MEDIA-SEQUENCE 值（基础 segment 号） */
        public long mediaSequence = 0;
        /** 当前 chunklist 中的完整 segment 数 */
        public int segmentCount = 0;
        /** 当前 chunklist 中的 partial segment（part）数 */
        public int partCount = 0;
        /** EXT-X-PART-HOLD-BACK 值（秒） */
        public double partHoldBack = 0;
        /** 下一个应请求的 msn（media sequence number） */
        public long nextMsn = 0;
        /** 下一个应请求的 part index */
        public int nextPart = 0;
        /** 该 chunklist 是否只含 partial segments（无完整 segment） */
        public boolean isPartialOnly = false;
    }

    /**
     * 解析 LL-HLS chunklist（含 _HLS_msn / _HLS_part 增量参数计算）
     * 返回 segments + LL-HLS 序列信息
     * @param m3u8Content chunklist 内容
     * @param prevInfo 上一次解析的结果（首轮传 null）
     * @return Map 含: segments, initSegmentUrl, partHoldBack, llHlsInfo
     */
    public static java.util.Map<String, Object> parseChunklistWithInit(String m3u8Content, LlHlsChunklistInfo prevInfo) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        List<SegmentInfo> segments = new ArrayList<>();
        String initSegmentUrl = null;
        double partHoldBack = 0;
        long mediaSequence = 0;
        int partCount = 0;
        boolean hasFullSegment = false;

        try (BufferedReader reader = new BufferedReader(new StringReader(m3u8Content))) {
            String line;
            double currentDuration = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // 解析 #EXT-X-MAP (init 片段)
                if (line.startsWith("#EXT-X-MAP:")) {
                    initSegmentUrl = extractMapUri(line);
                    log.debug("解析到 EXT-X-MAP: {}", initSegmentUrl);
                }

                // 解析 #EXT-X-MEDIA-SEQUENCE（媒体序列号）
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    String val = line.substring(line.indexOf(':') + 1).trim();
                    try {
                        mediaSequence = Long.parseLong(val);
                        log.debug("解析到 MEDIA-SEQUENCE: {}", mediaSequence);
                    } catch (NumberFormatException e) {
                        log.warn("无法解析 MEDIA-SEQUENCE 值: {}", val);
                    }
                }

                // 解析 #EXT-X-PART-HOLD-BACK（直播延迟控制）
                if (line.startsWith("#EXT-X-PART-HOLD-BACK:")) {
                    String valueStr = line.substring(line.indexOf(':') + 1).trim();
                    try {
                        partHoldBack = Double.parseDouble(valueStr);
                        log.debug("解析到 PART-HOLD-BACK: {}s", partHoldBack);
                    } catch (NumberFormatException e) {
                        log.warn("无法解析 PART-HOLD-BACK 值: {}", valueStr);
                    }
                }

                if (line.startsWith("#EXTINF:")) {
                    String durationStr = line.substring(line.indexOf(':') + 1).replace(",", "").trim();
                    try {
                        currentDuration = Double.parseDouble(durationStr);
                    } catch (NumberFormatException e) {
                        currentDuration = 0;
                    }
                } else if (line.length() > 0 && !line.startsWith("#")) {
                    SegmentInfo segment = new SegmentInfo();
                    segment.setUrl(line);
                    segment.setDuration(currentDuration);
                    segments.add(segment);
                    currentDuration = 0;
                }
            }
        } catch (IOException e) {
            log.error("解析片段列表失败: {}", e.getMessage());
        }

        // 统计完整 segment 数和 partial segment（part）数
        // LL-HLS: 带 EXTINF 但不带 #EXT-X-MAP 的是 partial segment
        // 完整 segment 有 EXTINF 描述，但 part 由 EXTINF + URL 构成且数量多于常规 segments
        // 区分方式：chunklist 中有 #EXT-X-MAP 则该 chunklist 含完整 segments；否则只有 parts
        int fullSegmentCount = 0;
        int partialPartCount = 0;

        // 判断依据：看是否有完整 segment（带 EXTINF 且之前有 EXT-X-MAP 的 segment）
        // 实际上：如果 initSegmentUrl != null，则该 chunklist 含完整 segments
        // 如果 initSegmentUrl == null，则该 chunklist 只含 partial parts
        // Chaturbate 的 LL-HLS：每个 segment 前有 EXT-X-MAP
        if (initSegmentUrl != null) {
            // 含完整 segments
            fullSegmentCount = segments.size();
            hasFullSegment = true;
        } else {
            // 只有 partial parts
            partialPartCount = segments.size();
            hasFullSegment = false;
        }

        // 计算下一个应请求的 msn 和 part
        LlHlsChunklistInfo llInfo = new LlHlsChunklistInfo();
        llInfo.mediaSequence = mediaSequence;
        llInfo.segmentCount = fullSegmentCount;
        llInfo.partCount = partialPartCount;
        llInfo.partHoldBack = partHoldBack;
        llInfo.isPartialOnly = !hasFullSegment;

        if (prevInfo == null) {
            // 首轮：请求整个 playlist，下次从当前位置继续
            if (hasFullSegment) {
                llInfo.nextMsn = mediaSequence + fullSegmentCount;
                llInfo.nextPart = 0;
            } else {
                llInfo.nextMsn = mediaSequence;
                llInfo.nextPart = partialPartCount;
            }
        } else {
            // 增量请求：基于上次状态 + 本次 chunklist 变化推算
            if (hasFullSegment) {
                // 本次 chunklist 有完整 segments
                if (mediaSequence > prevInfo.mediaSequence) {
                    // 进入新 segment
                    llInfo.nextMsn = mediaSequence + fullSegmentCount;
                    llInfo.nextPart = 0;
                } else {
                    // 同 segment 的新 parts（实际上不应出现在含完整 segment 的 chunklist 中）
                    llInfo.nextMsn = prevInfo.nextMsn;
                    llInfo.nextPart = fullSegmentCount;
                }
            } else {
                // 本次 chunklist 只有 partial parts
                if (mediaSequence > prevInfo.mediaSequence) {
                    // 新 segment 开始，parts 从 0
                    llInfo.nextMsn = mediaSequence;
                    llInfo.nextPart = partialPartCount;
                } else {
                    // 同 segment 的更多 parts
                    llInfo.nextMsn = prevInfo.mediaSequence;
                    llInfo.nextPart = Math.max(prevInfo.nextPart, partialPartCount);
                }
            }
        }

        result.put("segments", segments);
        result.put("initSegmentUrl", initSegmentUrl);
        result.put("partHoldBack", (long) partHoldBack);
        result.put("llHlsInfo", llInfo);
        return result;
    }

    /**
     * 兼容旧调用（首轮无 prevInfo）
     */
    public static java.util.Map<String, Object> parseChunklistWithInit(String m3u8Content) {
        return parseChunklistWithInit(m3u8Content, null);
    }

    /**
     * 从 #EXT-X-MAP 标签提取 URI
     * 格式: #EXT-X-MAP:URI="init.mp4"
     */
    private static String extractMapUri(String line) {
        int uriStart = line.indexOf("URI=");
        if (uriStart == -1) return null;
        
        uriStart += 4; // 跳过 "URI="
        
        // URI 可能被引号包裹
        if (line.charAt(uriStart) == '"') {
            uriStart++;
            int uriEnd = line.indexOf('"', uriStart);
            return line.substring(uriStart, uriEnd);
        } else {
            // 没有引号，到行尾或逗号
            int uriEnd = line.indexOf(',', uriStart);
            if (uriEnd == -1) {
                uriEnd = line.length();
            }
            return line.substring(uriStart, uriEnd);
        }
    }

    private static String extractAttribute(String attrs, String key) {
        String searchKey = key + "=";
        int start = attrs.indexOf(searchKey);
        if (start == -1) return null;

        start += searchKey.length();

        // 处理带引号和逗号分隔的值
        char firstChar = attrs.charAt(start);
        int end;

        if (firstChar == '"') {
            // 引号包裹的值
            start++; // 跳过开头的引号
            end = attrs.indexOf('"', start);
        } else {
            // 逗号或分号分隔的值
            end = attrs.indexOf(',', start);
            if (end == -1) {
                end = attrs.length();
            }
        }

        return attrs.substring(start, end);
    }

    /**
     * 主播放列表信息（包含视频流和音频流）
     */
    public static class MasterPlaylistInfo {
        private String format; // "fmp4" 或 "ts"
        private List<StreamInfo> videoStreams;
        private List<AudioStreamInfo> audioStreams;
        private String selectedVideoChunklist;  // 新增：选择的视频chunklist
        private String selectedAudioChunklist;  // 新增：选择的音频chunklist

        public List<StreamInfo> getVideoStreams() { return videoStreams; }
        public void setVideoStreams(List<StreamInfo> videoStreams) { this.videoStreams = videoStreams; }

        public List<AudioStreamInfo> getAudioStreams() { return audioStreams; }
        public void setAudioStreams(List<AudioStreamInfo> audioStreams) { this.audioStreams = audioStreams; }

        public String getSelectedVideoChunklist() { return selectedVideoChunklist; }
        public void setSelectedVideoChunklist(String selectedVideoChunklist) { this.selectedVideoChunklist = selectedVideoChunklist; }

        public String getSelectedAudioChunklist() { return selectedAudioChunklist; }
        public void setSelectedAudioChunklist(String selectedAudioChunklist) { this.selectedAudioChunklist = selectedAudioChunklist; }

        /**
         * 根据 GROUP-ID 获取对应的音频流
         */
        public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public AudioStreamInfo getAudioStreamByGroupId(String groupId) {
            if (audioStreams == null) return null;
            return audioStreams.stream()
                    .filter(a -> groupId.equals(a.getGroupId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 音频流信息 (从 #EXT-X-MEDIA:TYPE=AUDIO 解析)
     */
    public static class AudioStreamInfo {
        private String groupId;
        private String name;
        private boolean isDefault;
        private boolean autoselect;
        private String channels;
        private String chunklistUrl;

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public boolean isDefault() { return isDefault; }
        public void setDefault(boolean aDefault) { isDefault = aDefault; }

        public boolean isAutoselect() { return autoselect; }
        public void setAutoselect(boolean autoselect) { this.autoselect = autoselect; }

        public String getChannels() { return channels; }
        public void setChannels(String channels) { this.channels = channels; }

        public String getChunklistUrl() { return chunklistUrl; }
        public void setChunklistUrl(String chunklistUrl) { this.chunklistUrl = chunklistUrl; }
    }

    /**
     * 流信息 (从 master.m3u8 解析)
     */
    public static class StreamInfo {
        private int bandwidth;
        private String resolution;
        private String codecs;
        private String chunklistUrl;
        private String audioGroupId;  // 新增：音频流 GROUP-ID

        // Getters and Setters
        public int getBandwidth() { return bandwidth; }
        public void setBandwidth(int bandwidth) { this.bandwidth = bandwidth; }

        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }

        public String getCodecs() { return codecs; }
        public void setCodecs(String codecs) { this.codecs = codecs; }

        public String getChunklistUrl() { return chunklistUrl; }
        public void setChunklistUrl(String chunklistUrl) { this.chunklistUrl = chunklistUrl; }

        public String getAudioGroupId() { return audioGroupId; }
        public void setAudioGroupId(String audioGroupId) { this.audioGroupId = audioGroupId; }

        /**
         * 从分辨率字符串提取高度 (如 "1920x1080" -> 1080)
         */
        public int getResolutionHeight() {
            if (resolution == null) return 0;
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /**
     * 片段信息 (从 chunklist.m3u8 解析)
     */
    public static class SegmentInfo {
        private String url;
        private double duration;

        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public double getDuration() { return duration; }
        public void setDuration(double duration) { this.duration = duration; }
    }


    /**
     * 判断 chunklist 是否为 TS 格式（传统 HLS）
     */
    public static boolean isTsFormat(String chunklistContent) {
        if (chunklistContent == null) return false;
        return !chunklistContent.contains("#EXT-X-MAP");
    }

    /**
     * 解析 TS 格式的 chunklist（传统 HLS）
     */
    public static List<SegmentInfo> parseTsChunklist(String chunklistContent) {
        List<SegmentInfo> segments = new ArrayList<>();
        if (chunklistContent == null || !chunklistContent.contains("#EXTINF")) {
            return segments;
        }

        String[] lines = chunklistContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXTINF")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String urlLine = lines[j].trim();
                    if (!urlLine.isEmpty() && !urlLine.startsWith("#")) {
                        SegmentInfo segment = new SegmentInfo();
                        segment.setUrl(urlLine);
                        segments.add(segment);
                        break;
                    }
                }
            }
        }
        return segments;
    }
}
