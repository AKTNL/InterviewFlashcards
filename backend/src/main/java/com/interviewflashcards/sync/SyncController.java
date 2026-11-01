package com.interviewflashcards.sync;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync")
public record SyncController(SyncService service, ApiResponses responses, SyncNotifications notifications) {
    @GetMapping("/notifications")
    public org.springframework.web.context.request.async.DeferredResult<ApiResponse<?>> notifications(
            @AuthenticationPrincipal AccountPrincipal user, @RequestParam UUID dataEpoch, @RequestParam long since,
            @RequestParam String learningDate, HttpServletRequest request) {
        return notifications.listen(user,dataEpoch,since,learningDate,request);
    }
    @GetMapping("/revision")
    public ApiResponse<?> revision(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(service.snapshot(user.id(), false), request);
    }
    @GetMapping("/snapshot")
    public ApiResponse<?> snapshot(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(service.snapshot(user.id(), true), request);
    }
    @GetMapping("/changes")
    public ApiResponse<?> changes(@AuthenticationPrincipal AccountPrincipal user, @RequestParam UUID dataEpoch,
                                   @RequestParam long since, HttpServletRequest request) throws java.io.IOException {
        return responses.success(service.changes(user.id(),dataEpoch,since),request);
    }
    @GetMapping("/receipts/{operationId}")
    public ApiResponse<?> receipt(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID operationId,
                                  @RequestParam UUID dataEpoch, HttpServletRequest request) throws java.io.IOException {
        return responses.success(service.receipt(user.id(), dataEpoch, operationId), request);
    }
}
