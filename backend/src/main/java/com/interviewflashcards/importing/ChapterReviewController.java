package com.interviewflashcards.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public record ChapterReviewController(ChapterReviewService review, ApiResponses responses) {
    @GetMapping("/api/chapters/{id}/cards")
    public ApiResponse<?> cards(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id, HttpServletRequest request) {
        return responses.success(review.cards(user.id(), id), request);
    }
    @GetMapping("/api/chapters/{id}/review")
    public ApiResponse<?> get(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id, HttpServletRequest request) {
        return responses.success(review.view(user.id(), id), request);
    }
    @PostMapping("/api/chapters/{id}/review")
    public ApiResponse<?> write(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id,
                               @Valid @RequestBody Write body, HttpServletRequest request) throws IOException {
        return responses.success(review.write(user.id(), id, body), request);
    }
    public record Write(@NotNull UUID operationId, @NotNull UUID dataEpoch, @NotNull @PositiveOrZero Long expectedVersion,
                        @NotBlank @Pattern(regexp="EDIT|MERGE|SPLIT|EXCLUDE|PROGRESS|CONFIRM|ABANDON") String action,
                        @NotNull JsonNode payload) {}
}
