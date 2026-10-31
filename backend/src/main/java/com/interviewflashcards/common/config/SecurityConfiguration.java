package com.interviewflashcards.common.config;

import com.interviewflashcards.common.web.ApiResponses;
import com.interviewflashcards.common.web.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiResponses responses) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.writeError(ErrorCode.UNAUTHENTICATED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                responses.writeError(ErrorCode.FORBIDDEN, request, response)))
                // CSRF remains enabled. P0-03 adds the session login and CSRF token contract.
                .build();
    }
}
