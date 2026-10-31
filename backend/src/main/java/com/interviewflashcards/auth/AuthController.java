package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public record AuthController(AccountService accounts, ApiResponses responses) {
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
