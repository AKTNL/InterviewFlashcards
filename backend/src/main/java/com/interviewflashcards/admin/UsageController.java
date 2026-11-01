package com.interviewflashcards.admin;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public record UsageController(UsageService usage,ApiResponses responses) {
    @PostMapping("/api/usage/heartbeat") public ApiResponse<?> beat(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Beat body,HttpServletRequest request){
        var session=request.getSession(false);if(session==null)throw new ApiFailure(ErrorCode.UNAUTHENTICATED);
        return responses.success(usage.heartbeat(user,session.getId(),body.streamId(),body.sequence(),body.active()),request);
    }
    public record Beat(@NotNull UUID streamId,@NotNull @Min(0) Long sequence,@NotNull Boolean active) implements AdminController.Strict {}
}
