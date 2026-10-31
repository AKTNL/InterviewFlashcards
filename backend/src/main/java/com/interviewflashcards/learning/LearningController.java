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
