package com.interviewflashcards.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiResponses responses;

    public ApiExceptionHandler(ApiResponses responses) { this.responses = responses; }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiError> failure(ApiFailure exception, HttpServletRequest request) {
        return responses.error(exception.code(), request, List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(HttpServletRequest request) {
        return responses.error(ErrorCode.FILE_TOO_LARGE, request, List.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiError> invalidParameter(HttpServletRequest request) {
        return responses.error(ErrorCode.INVALID_REQUEST, request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return responses.error(ErrorCode.VALIDATION_ERROR, request,
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField()).distinct().sorted().toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> validation(HttpServletRequest request) {
        return responses.error(ErrorCode.VALIDATION_ERROR, request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpServletRequest request) {
        return responses.error(ErrorCode.INVALID_REQUEST, request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return responses.error(ErrorCode.NOT_FOUND, request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpServletRequest request) {
        return responses.error(ErrorCode.METHOD_NOT_ALLOWED, request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpServletRequest request) {
        return responses.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request, List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(Exception exception, HttpServletRequest request) {
        log.warn("database unavailable type={}", exception.getClass().getSimpleName());
        return responses.error(ErrorCode.SERVICE_UNAVAILABLE, request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled error type={}", exception.getClass().getSimpleName());
        return responses.error(ErrorCode.INTERNAL_ERROR, request, List.of());
    }
}
