package com.interviewflashcards.common.config;

import com.interviewflashcards.common.web.ApiResponses;
import com.interviewflashcards.common.web.ErrorCode;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.auth.AccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiResponses responses, AccountService accounts, org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        return http
                .addFilterBefore(new com.interviewflashcards.auth.AuthGuardFilter(jdbc,responses), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/csrf", "/api/auth/registration").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/invite/validate").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            responses.mapper().writeValue(response.getOutputStream(), responses.success(
                                    accounts.profile((AccountPrincipal) authentication.getPrincipal()), request));
                        })
                        .failureHandler((request, response, exception) ->
                                responses.writeError(ErrorCode.BAD_CREDENTIALS, request, response))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            responses.mapper().writeValue(response.getOutputStream(), responses.success(true, request));
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.writeError(ErrorCode.UNAUTHENTICATED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                responses.writeError(exception instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN,
                                        request, response)))
                .build();
    }
}
