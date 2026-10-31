package com.interviewflashcards.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public record LearningController(LearningService learning, ApiResponses responses) {
    public record Start(@NotNull UUID operationId,@NotNull UUID dataEpoch,
                        @NotEmpty @Size(max=100) List<@NotNull UUID> cardIds) {}
    public record Write(@NotNull UUID operationId,@NotNull UUID dataEpoch,
                        @NotNull @PositiveOrZero Long expectedVersion,
                        @NotBlank @Pattern(regexp="RATE|UNDO|RELEARN|SKIP") String action,@NotNull JsonNode payload) {}
    public record Preferences(@NotNull UUID operationId,@NotNull UUID dataEpoch,@NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @Min(0) @Max(100) Integer dailyNewCount,@NotNull @Min(1) @Max(100) Integer reviewBatchSize,
        @Min(1) @Max(1440) Integer referenceMinutes,@NotNull @Size(max=1000) List<@NotNull UUID> chapterIds) {}
    public record Plan(@NotNull UUID operationId,@NotNull UUID dataEpoch,@NotBlank @Pattern(regexp="MIXED|REVIEW|NEW") String mode) {}
    public record Resolve(@NotNull UUID operationId,@NotNull UUID dataEpoch,@NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Long expectedReviewVersion,@NotBlank @Pattern(regexp="CURRENT|CANDIDATE") String choice) {}
    @PostMapping("/api/sync/conflicts/{id}/resolve")
    public ApiResponse<?> resolve(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,
                                 @Valid @RequestBody Resolve body,HttpServletRequest request) throws IOException {
        return responses.success(learning.resolve(user.id(),id,body),request);
    }
    @GetMapping("/api/preferences")
    public ApiResponse<?> preferences(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(learning.preferences(user.id()),request);
    }
    @PostMapping("/api/preferences")
    public ApiResponse<?> preferences(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Preferences body,HttpServletRequest request) throws IOException {
        return responses.success(learning.savePreferences(user.id(),body),request);
    }
    @GetMapping("/api/today")
    public ApiResponse<?> today(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(learning.today(user.id()),request);
    }
    @PostMapping("/api/learning-sessions/daily")
    public ApiResponse<?> daily(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Plan body,HttpServletRequest request) throws IOException {
        return responses.success(learning.daily(user.id(),body),request);
    }
    @PostMapping("/api/learning-sessions")
    public ApiResponse<?> start(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Start body,HttpServletRequest request) throws IOException {
        return responses.success(learning.start(user.id(),body),request);
    }
    @GetMapping("/api/learning-sessions/{id}")
    public ApiResponse<?> session(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,HttpServletRequest request) {
        return responses.success(learning.session(user.id(),id),request);
    }
    @GetMapping("/api/review-events/summary")
    public ApiResponse<?> summary(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(learning.summary(user.id()),request);
    }
    @PostMapping("/api/learning-sessions/{id}/review")
    public ApiResponse<?> write(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,@Valid @RequestBody Write body,HttpServletRequest request) throws IOException {
        return responses.success(learning.write(user.id(),id,body),request);
    }
}
