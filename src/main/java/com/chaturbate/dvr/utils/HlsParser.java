package com.chaturbate.dvr.utils;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

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
    public static java.util.Map<String, Object> parseChunklistWithInit(String m3u8Content) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        List<SegmentInfo> segments = new ArrayList<>();
        String initSegmentUrl = null;
        Long partHoldBack = null;

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
                
                // 调试：打印所有 # 开头的行
                if (line.startsWith("#")) {
                    log.trace("chunklist 标签: {}", line);
                }
                
                // 解析 #EXT-X-PART-HOLD-BACK（直播延迟控制）
                // 格式: #EXT-X-PART-HOLD-BACK:1.0
                if (line.startsWith("#EXT-X-PART-HOLD-BACK:")) {
                    String valueStr = line.substring(line.indexOf(':') + 1).trim();
                    try {
                        partHoldBack = (long) Double.parseDouble(valueStr);
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

        result.put("segments", segments);
        result.put("initSegmentUrl", initSegmentUrl);
        result.put("partHoldBack", partHoldBack);
        return result;
    }

    /**
     * 从 #EXT-X-MAP 标签提取 URI
     * 格式: #EXT-X-MAP:URI="init.mp4"
     */
    private static String extractMapUri(String line) {
        int uriStart = line.indexOf("URI=");
        if (uriStart == -1) return null;
        
        uriStart += 5; // 跳过 "URI="
        
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
}
