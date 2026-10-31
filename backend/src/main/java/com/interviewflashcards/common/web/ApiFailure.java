package com.interviewflashcards.common.web;

public class ApiFailure extends RuntimeException {
    private final ErrorCode code;
    private final Object details;
    public ApiFailure(ErrorCode code) { this(code, null); }
    public ApiFailure(ErrorCode code, Object details) { super(code.name()); this.code = code; this.details = details; }
    public ErrorCode code() { return code; }
    public Object details() { return details; }
}
