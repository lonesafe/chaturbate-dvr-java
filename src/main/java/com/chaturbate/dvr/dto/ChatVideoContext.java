package com.chaturbate.dvr.dto;

import lombok.Data;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chaturbate API 返回的直播间上下文数据
 * 对应接口: https://zh-hans.chaturbate.com/api/chatvideocontext/{username}/
 * 使用 org.json 库解析 JSON
 */
@Data
public class ChatVideoContext {

    /** 观看者UID */
    private String viewerUid;

    /** 是否年龄验证通过 */
    private Boolean ageVerified;

    /** 主播年龄 */
    private Integer age;

    /** 房间状态: public(公开直播), private(私密), offline(离线) */
    private String roomStatus;

    /** 观看人数 */
    private Integer numViewers;

    /** 观看者用户名 */
    private String viewerUsername;

    /** 主播用户名 */
    private String broadcasterUsername;

    /** 房间标题 */
    private String roomTitle;

    /** 房间UID */
    private String roomUid;

    /** 主播性别 */
    private String broadcasterGender;

    /** HLS直播源地址 - 这是录制需要的地址 */
    private String hlsSource;

    /** 是否宽屏 */
    private Boolean widescreen;

    /** 是否竖屏 */
    private Boolean portrait;

    /** 是否允许私密秀 */
    private Boolean allowPrivateShows;

    /** 私密秀价格 */
    private Integer privateShowPrice;

    /** 边缘区域 */
    private String edgeRegion;

    /**
     * 从 JSONObject 解析数据
     * @param json JSONObject 对象
     */
    public void parseFromJSONObject(JSONObject json) {
        try {
            this.viewerUid = json.optString("viewer_uid", null);
            this.ageVerified = json.optBoolean("is_age_verified", false);
            this.age = json.optInt("age", 0);
            this.roomStatus = json.optString("room_status", "offline");
            this.numViewers = json.optInt("num_viewers", 0);
            this.viewerUsername = json.optString("viewer_username", null);
            this.broadcasterUsername = json.optString("broadcaster_username", null);
            this.roomTitle = json.optString("room_title", null);
            this.roomUid = json.optString("room_uid", null);
            this.broadcasterGender = json.optString("broadcaster_gender", null);
            this.hlsSource = json.optString("hls_source", null);
            this.widescreen = json.optBoolean("is_widescreen", false);
            this.portrait = json.optBoolean("is_portrait", false);
            this.allowPrivateShows = json.optBoolean("allow_private_shows", false);
            this.privateShowPrice = json.optInt("private_show_price", 0);
            this.edgeRegion = json.optString("edge_region", null);
        } catch (JSONException e) {
            throw new RuntimeException("解析 ChatVideoContext JSON 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSONObject 创建 ChatVideoContext 实例的静态工厂方法
     * @param json JSONObject 对象
     * @return ChatVideoContext 实例
     */
    public static ChatVideoContext fromJSONObject(JSONObject json) {
        ChatVideoContext context = new ChatVideoContext();
        context.parseFromJSONObject(json);
        return context;
    }

    /** 是否在线直播 (room_status == "public") */
    public boolean isPublicLive() {
        return "public".equalsIgnoreCase(roomStatus);
    }

    /** 是否离线 */
    public boolean isOffline() {
        return "offline".equalsIgnoreCase(roomStatus);
    }

    /** 是否私密中 */
    public boolean isPrivate() {
        return "private".equalsIgnoreCase(roomStatus);
    }
}
