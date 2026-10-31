package com.interviewflashcards.common.web;

import java.util.List;

public record ApiError(Detail error, ApiResponse.Meta meta) {
    public record Detail(ErrorCode code, String message, List<String> fields) {}
}
