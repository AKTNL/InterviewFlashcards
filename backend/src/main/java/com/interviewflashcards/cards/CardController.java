package com.interviewflashcards.cards;

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
public record CardController(CardService cards, DeckManagementService decks, ApiResponses responses) {
    @GetMapping("/api/cards/decks")
    public ApiResponse<?> decks(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(decks.overview(user.id()), request);
    }
    @GetMapping("/api/cards/catalog")
    public ApiResponse<?> catalog(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(cards.catalog(user.id()), request);
    }
    @GetMapping("/api/cards")
    public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal user,
            @RequestParam(required=false) UUID deckId, @RequestParam(required=false) UUID moduleId,
            @RequestParam(required=false) UUID chapterId, @RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(defaultValue="ALL") @Pattern(regexp="ALL|UNLEARNED|LEARNED|DUE|WEAK|FAVORITE|CHECK|DUPLICATE") String filter,
            @RequestParam(defaultValue="0") @Min(0) int offset, @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,
            HttpServletRequest request) {
        return responses.success(cards.list(user.id(), deckId, moduleId, chapterId, q, filter, offset, limit), request);
    }
    @GetMapping("/api/cards/{id}")
    public ApiResponse<?> detail(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id, HttpServletRequest request) {
        return responses.success(cards.detail(user.id(), id), request);
    }
    @PostMapping("/api/cards/{id}")
    public ApiResponse<?> write(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id,
                               @Valid @RequestBody Write body, HttpServletRequest request) throws IOException {
        return responses.success(cards.write(user.id(), id, body), request);
    }
    public record Write(@NotNull UUID operationId, @NotNull UUID dataEpoch, @NotNull @PositiveOrZero Long expectedVersion,
                        @NotBlank @Pattern(regexp="ANNOTATION|METADATA") String action, @NotNull JsonNode payload) {}
}
