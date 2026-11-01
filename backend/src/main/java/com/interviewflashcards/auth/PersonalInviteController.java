package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public record PersonalInviteController(PersonalInviteService invites, ApiResponses responses) {
    @GetMapping("/api/account/invites")
    public ApiResponse<PersonalInviteService.Overview> overview(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(invites.overview(user.id()),request);
    }
    @PostMapping("/api/account/invites")
    public ApiResponse<PersonalInviteService.Generated> generate(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(invites.generate(user.id()),request);
    }
    @PostMapping("/api/account/invites/{id}/revoke")
    public ApiResponse<Boolean> revoke(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID id,HttpServletRequest request) {
        invites.revoke(user.id(),id);return responses.success(true,request);
    }
    @PostMapping("/api/auth/invite/validate")
    public ApiResponse<Boolean> validate(@Valid @RequestBody Validation body,HttpServletRequest request) {
        invites.validate(body.invite());return responses.success(true,request);
    }
    public record Validation(@NotNull @Size(max=100) String invite) {
        @Override public String toString() { return "Validation[redacted]"; }
    }
}
