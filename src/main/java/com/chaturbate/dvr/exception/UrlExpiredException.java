package com.chaturbate.dvr.exception;

/**
 * URL 过期异常
 * 当 HTTP 请求返回 403 时抛出，表示 m3u8 token 已过期需要刷新
 */
public class UrlExpiredException extends RuntimeException {

    private final String url;
    private final int errorCode;

    public UrlExpiredException(String message, String url, int errorCode) {
        super(message);
        this.url = url;
        this.errorCode = errorCode;
    }

    public String getUrl() {
        return url;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
