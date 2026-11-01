package com.interviewflashcards.common.web;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    GROUP_LIMITED(HttpStatus.CONFLICT, "小组数量、成员或待接受邀请已达上限"),
    GROUP_INVITE_UNAVAILABLE(HttpStatus.CONFLICT, "小组邀请已过期、撤销或小组已解散，请联系组主"),
    SHARE_UNAVAILABLE(HttpStatus.CONFLICT, "邀请已过期、撤销或领取完毕，请联系分享者"),
    SHARE_RECIPIENT_INVALID(HttpStatus.BAD_REQUEST, "接收账号不可用，请核对已注册的用户名"),
    SHARE_EMPTY(HttpStatus.CONFLICT, "题库尚无已确认的正式卡片"),
    REGISTRATION_CLOSED(HttpStatus.FORBIDDEN, "注册暂未开放，请联系维护者"),
    INVITE_INVALID(HttpStatus.BAD_REQUEST, "邀请码不可用，请联系维护者获取有效邀请码"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "此用户名不可用，请选择其他用户名；若刚刚提交过注册，请先尝试登录"),
    ACCOUNT_NAME_INVALID(HttpStatus.BAD_REQUEST, "用户名需为 3～32 位小写字母、数字或下划线，以字母开头，不能使用保留名"),
    PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "密码至少 12 个字符、最多 72 个 UTF-8 字节，不含控制字符"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请 15 分钟后重试"),
    ACCOUNT_CHANGED(HttpStatus.CONFLICT, "登录账号已变化，请重新确认身份后继续"),
    QUOTA_EXCEEDED(HttpStatus.CONFLICT, "个人存储或来源数量已达上限，请查看我的资料中的剩余额度"),
    TASK_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "正在处理的任务已达上限，请等待任务结束后再试"),
    CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "服务存储容量不足，请联系维护者后再试"),
    DATA_PREVIEW_STALE(HttpStatus.CONFLICT, "数据已变化，请重新读取影响预览并确认"),
    RESTORE_STALE(HttpStatus.CONFLICT, "当前数据已变化，请刷新替换影响预览后重新确认"),
    BACKUP_INVALID(HttpStatus.BAD_REQUEST, "备份格式、文件校验或数据关联不合法，当前数据未替换"),
    SESSION_EXPIRED(HttpStatus.CONFLICT, "学习会话已结束或跨天，请重新进入练习"),
    EARLY_REVIEW_CONFIRMATION(HttpStatus.CONFLICT, "此题尚未到期，请确认提前练习将更新安排"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求格式不正确"),
    CHAPTER_CLOSED(HttpStatus.CONFLICT, "本章已导入或已放弃；已导入内容请通过编辑修正"),
    CHECK_REQUIRED(HttpStatus.CONFLICT, "仍有待检查内容，请明确选择保留标记后导入"),
    EMPTY_CHAPTER(HttpStatus.CONFLICT, "本章没有可导入的草稿"),
    IMPORT_NOT_RETRYABLE(HttpStatus.CONFLICT, "仅失败的解析任务可以重试，请刷新任务状态"),
    IMPORT_CONTENT_EXISTS(HttpStatus.CONFLICT, "任务已有草稿，禁止重新解析覆盖，请继续检查现有内容"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "资料已被其他操作修改，请核对后重新保存"),
    EPOCH_EXPIRED(HttpStatus.CONFLICT, "数据已恢复或替换，请核对本地内容"),
    OPERATION_ID_REUSED(HttpStatus.CONFLICT, "操作编号对应的内容不一致，请保留内容并核对"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码不正确"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "安全凭证已过期，请刷新后重试"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件不能超过 50 MiB"),
    INVALID_PDF(HttpStatus.BAD_REQUEST, "请选择可读取、未加密且包含页面的 PDF 文件"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "文件服务暂时不可用，请稍后重试"),
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
