package com.interviewflashcards.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.time.LearningTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public record ApiResponses(LearningTime time, ObjectMapper mapper) {
    public ApiResponse.Meta meta(HttpServletRequest request) {
        return new ApiResponse.Meta(RequestTraceFilter.requestId(request), time.now());
    }

    public <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return new ApiResponse<>(data, meta(request));
    }

    public ResponseEntity<ApiError> error(ErrorCode code, HttpServletRequest request, List<String> fields) {
        return ResponseEntity.status(code.status()).body(body(code, request, fields));
    }

    public void writeError(ErrorCode code, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body(code, request, List.of()));
    }

    private ApiError body(ErrorCode code, HttpServletRequest request, List<String> fields) {
        return new ApiError(new ApiError.Detail(code, code.message(), fields), meta(request));
    }
}
