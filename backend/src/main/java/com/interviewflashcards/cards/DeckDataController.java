package com.interviewflashcards.cards;

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
public record DeckDataController(DeckDataService service, ApiResponses responses) {
    public record Confirm(@NotNull UUID operationId, @NotNull UUID dataEpoch,
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Pattern(regexp="DELETE|RESET") String action, @AssertTrue boolean confirmed) {}
    @GetMapping("/api/deck-data")
    public ApiResponse<?> decks(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(service.decks(user.id()), request);
    }
    @GetMapping("/api/deck-data/{id}/preview")
    public ApiResponse<?> preview(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id, HttpServletRequest request) {
        return responses.success(service.preview(user.id(), id), request);
    }
    @PostMapping("/api/deck-data/{id}")
    public ApiResponse<?> confirm(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id,
        @Valid @RequestBody Confirm body, HttpServletRequest request) throws IOException {
        return responses.success(service.confirm(user.id(), id, body), request);
    }
}
