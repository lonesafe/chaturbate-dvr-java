package com.chaturbate.dvr.service;

import com.chaturbate.dvr.dto.ChatVideoContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Chaturbate API 服务
 * 负责调用直播间接口获取直播状态
 * 使用 org.json 库解析 JSON
 * 从数据库加载配置（system_config 表）
 */
@Slf4j
@Service
public class ChaturbateApiService {

    @Autowired
    private SystemConfigService configService;

    // 配置项（从数据库加载，带默认值）
    private String getApiBaseUrl() {
        return configService.getConfigValue("api_base_url", 
            "https://zh-hans.chaturbate.com/api/chatvideocontext/");
    }

    private String getCookie() {
        return configService.getConfigValue("cookie", "");
    }

    private String getUserAgent() {
        return configService.getConfigValue("user_agent", 
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
    }

    /**
     * 获取直播间上下文信息
     *
     * @param username 主播用户名
     * @return ChatVideoContext 或 null (如果获取失败)
     */
    public ChatVideoContext getChatVideoContext(String username) {
        String url = getApiBaseUrl() + URLEncoder.encode(username, StandardCharsets.UTF_8) + "/";

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setUserAgent(getUserAgent())
                .build()) {

            HttpGet httpGet = new HttpGet(url);
            httpGet.setHeader("Cookie", getCookie());
            httpGet.setHeader("User-Agent", getUserAgent());
            httpGet.setHeader("Accept", "application/json");
            httpGet.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            httpGet.setHeader("Referer", "https://zh-hans.chaturbate.com/");

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (statusCode == 200) {
                    // 使用 org.json 解析 JSON
                    JSONObject json = new JSONObject(responseBody);
                    ChatVideoContext context = ChatVideoContext.fromJSONObject(json);
                    log.debug("获取直播间 [{}] 状态成功: {}", username, context.getRoomStatus());
                    return context;
                } else if (statusCode == 403) {
                    log.error("获取直播间 [{}] 失败: 403 Forbidden - Cookie 可能已过期", username);
                    return null;
                } else {
                    log.error("获取直播间 [{}] 失败: HTTP {}", username, statusCode);
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("获取直播间 [{}] 信息时发生异常: {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * 检查直播间是否正在公开直播
     *
     * @param username 主播用户名
     * @return true=正在公开直播
     */
    public boolean isPublicLive(String username) {
        ChatVideoContext context = getChatVideoContext(username);
        return context != null && context.isPublicLive();
    }

    /**
     * 获取直播 HLS 地址
     *
     * @param username 主播用户名
     * @return HLS 地址或 null
     */
    public String getHlsSource(String username) {
        ChatVideoContext context = getChatVideoContext(username);
        if (context != null && context.isPublicLive()) {
            return context.getHlsSource();
        }
        return null;
    }
}
