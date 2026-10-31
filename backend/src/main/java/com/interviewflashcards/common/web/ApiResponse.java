package com.interviewflashcards.common.web;

import java.time.Instant;

public record ApiResponse<T>(T data, Meta meta) {
    public record Meta(String requestId, Instant serverTime) {}
}
