package com.interviewflashcards.common.web;

public class ApiFailure extends RuntimeException {
    private final ErrorCode code;
    public ApiFailure(ErrorCode code) { super(code.name()); this.code = code; }
    public ErrorCode code() { return code; }
}
