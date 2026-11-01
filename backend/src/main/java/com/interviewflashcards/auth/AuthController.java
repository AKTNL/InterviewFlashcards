package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
public record AuthController(AccountService accounts, ApiResponses responses, RegistrationService registrations, RegistrationProperties registration) {
    @GetMapping("/api/auth/registration")
    public ApiResponse<Boolean> registration(HttpServletRequest request) { return responses.success(registration.enabled(), request); }
    @PostMapping("/api/auth/register")
    public ApiResponse<Boolean> register(@Valid @RequestBody Register body, HttpServletRequest request) {
        registrations.register(body.invite(),body.username(),body.password(),body.nickname());
        return responses.success(true,request);
    }
    @PostMapping("/api/auth/password")
    public ApiResponse<Boolean> password(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody Password body, HttpServletRequest request) {
        registrations.changePassword(user.id(),body.currentPassword(),body.newPassword());
        if (request.getSession(false)!=null) request.getSession(false).invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return responses.success(true,request);
    }
    public record Register(@NotNull @Size(max=100) String invite, @NotNull @Size(max=100) String username,
                           @NotNull @Size(max=72) String password, @Size(max=60) String nickname) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Password(@NotNull @Size(max=200) String currentPassword, @NotNull @Size(max=72) String newPassword) {
        @Override public String toString() { return "Password[redacted]"; }
    }
    @GetMapping("/api/auth/csrf")
    public ApiResponse<Token> csrf(CsrfToken token, HttpServletRequest request) {
        return responses.success(new Token(token.getHeaderName(), token.getToken()), request);
    }
    @GetMapping("/api/auth/me")
    public ApiResponse<AccountService.Profile> me(@AuthenticationPrincipal AccountPrincipal principal,
                                                HttpServletRequest request) {
        return responses.success(accounts.profile(principal), request);
    }
    public record Token(String headerName, String token) {}
}
