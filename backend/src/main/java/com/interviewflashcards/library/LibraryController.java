package com.interviewflashcards.library;

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
public record LibraryController(LibraryService library,ApiResponses responses) {
    @GetMapping("/api/library") public ApiResponse<?> catalog(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r) {
        return responses.success(library.catalog(page,size),r);
    }
    @GetMapping("/api/library/claims") public ApiResponse<?> claims(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest r) {
        return responses.success(library.claims(user.id()),r);
    }
    @PostMapping("/api/library/{id}/claim") public ApiResponse<?> claim(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,@Valid @RequestBody Claim c,HttpServletRequest r) throws IOException {
        return responses.success(library.claim(user.id(),id,c),r);
    }
    public record Claim(@NotNull UUID operationId,@NotNull UUID dataEpoch,@Positive long contentVersion,boolean confirmed) {}
    public record Transition(@NotNull UUID operationId,@NotNull UUID dataEpoch,@PositiveOrZero long expectedVersion,
                             @PositiveOrZero long expectedRevision,@Pattern(regexp="PUBLISH|UNLIST|DELETE") @NotNull String action,
                             boolean confirmed,boolean rightsConfirmed) {}
}
