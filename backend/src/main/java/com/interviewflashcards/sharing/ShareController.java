package com.interviewflashcards.sharing;

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
@RequestMapping("/api/shares")
public record ShareController(ShareService service, ApiResponses responses) {
    public record Create(@NotNull UUID operationId, @NotNull UUID dataEpoch, @NotNull UUID deckId,
        @NotNull @PositiveOrZero Long expectedRevision, @NotBlank @Size(max=100) String recipient,
        @Min(1) @Max(168) int expiresInHours, @AssertTrue boolean rightsConfirmed) {}
    public record Claim(@NotNull UUID operationId, @NotNull UUID dataEpoch, @AssertTrue boolean confirmed) {}
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal u,HttpServletRequest r) {
        return responses.success(service.list(u.id()),r);
    }
    @GetMapping("/decks/{id}/preview") public ApiResponse<?> preview(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) {
        return responses.success(service.preview(u.id(),id),r);
    }
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal u,@Valid @RequestBody Create c,HttpServletRequest r) throws IOException {
        return responses.success(service.create(u.id(),c),r);
    }
    @GetMapping("/{id}") public ApiResponse<?> invitation(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) {
        return responses.success(service.invitation(u.id(),id),r);
    }
    @PostMapping("/{id}/claim") public ApiResponse<?> claim(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@Valid @RequestBody Claim c,HttpServletRequest r) throws IOException {
        return responses.success(service.claim(u.id(),id,c),r);
    }
    @PostMapping("/{id}/revoke") public ApiResponse<?> revoke(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) {
        return responses.success(service.revoke(u.id(),id),r);
    }
}
