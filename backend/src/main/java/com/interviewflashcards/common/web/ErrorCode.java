package com.interviewflashcards.common.web;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求格式不正确"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败，请凭请求编号排查");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
