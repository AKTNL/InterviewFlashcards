package com.interviewflashcards.importing;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public record ImportController(ImportService imports,ApiResponses responses) {
    @GetMapping("/api/import-jobs")
    public ApiResponse<?> jobs(@AuthenticationPrincipal AccountPrincipal user,@RequestParam(required=false) UUID sourceId,HttpServletRequest request) { return responses.success(imports.jobs(user.id(),sourceId),request); }
    @GetMapping("/api/import-jobs/{id}")
    public ApiResponse<?> job(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,HttpServletRequest request) { return responses.success(imports.job(user.id(),id),request); }
    @PostMapping("/api/import-jobs/{id}/retry")
    public ApiResponse<?> retry(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,@Valid @RequestBody Retry body,HttpServletRequest request) throws IOException {
        return responses.success(imports.retry(user.id(),id,new MutationService.Command(body.operationId(),body.dataEpoch()),body.expectedVersion()),request);
    }
    public record Retry(@NotNull UUID operationId,@NotNull UUID dataEpoch,@NotNull @PositiveOrZero Long expectedVersion) {}
    @GetMapping("/api/import-jobs/{id}/chapters")
    public ApiResponse<?> chapters(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,HttpServletRequest request) { return responses.success(imports.chapters(user.id(),id),request); }
    @GetMapping("/api/chapters/{id}/drafts")
    public ApiResponse<?> drafts(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) int offset,@RequestParam(defaultValue="20") @Min(1) @Max(50) int limit,HttpServletRequest request) { return responses.success(imports.drafts(user.id(),id,offset,limit),request); }
    @GetMapping("/api/drafts/{id}")
    public ApiResponse<?> draft(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,HttpServletRequest request) { return responses.success(imports.draft(user.id(),id),request); }
}
