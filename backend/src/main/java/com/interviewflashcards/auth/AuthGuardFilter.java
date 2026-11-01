package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed inside Spring Security, after the context and before login/controller handling. */
public final class AuthGuardFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ApiResponses responses;
    public AuthGuardFilter(JdbcTemplate jdbc, ApiResponses responses) { this.jdbc=jdbc; this.responses=responses; }
    private boolean allowed(String dimension, int limit) {
        // PostgreSQL clock and atomic upsert also work across restarts and concurrent requests.
        Integer attempts=jdbc.queryForObject("""
            insert into auth_rate_bucket(bucket,window_start,attempts) values(?,current_timestamp,1)
            on conflict(bucket) do update set
              attempts=case when auth_rate_bucket.window_start < current_timestamp-interval '15 minutes' then 1 else auth_rate_bucket.attempts+1 end,
              window_start=case when auth_rate_bucket.window_start < current_timestamp-interval '15 minutes' then current_timestamp else auth_rate_bucket.window_start end
            returning attempts
            """,Integer.class,AccountRules.digest(dimension));
        return attempts<=limit;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String path=request.getRequestURI().substring(request.getContextPath().length());
            if ("POST".equals(request.getMethod()) && List.of("/api/auth/login","/api/auth/register","/api/auth/password","/api/auth/invite/validate","/api/account/invites").contains(path)) {
                // Never read X-Forwarded-For here: only the connector's trusted remote address.
                String ratePath=path.equals("/api/auth/invite/validate")?"/api/auth/register":path;
                boolean source=allowed(ratePath+":ip:"+request.getRemoteAddr(),path.endsWith("login")?60:20);
                boolean account=!path.endsWith("login") || allowed("login:account:"+AccountRules.normalize(request.getParameter("username")),20);
                if (!source || !account) { responses.writeError(ErrorCode.RATE_LIMITED,request,response); return; }
            }
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if (authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal user) {
                if ("POST".equals(request.getMethod()) && path.equals("/api/account/invites") && !allowed("invite:user:"+user.id(),20)) {
                    responses.writeError(ErrorCode.RATE_LIMITED,request,response);return;
                }
                var versions=jdbc.queryForList("select credential_version,enabled,role,must_change_password from app_user where id=?",user.id());
                if (versions.isEmpty() || !Boolean.TRUE.equals(versions.getFirst().get("enabled")) || ((Number)versions.getFirst().get("credential_version")).longValue()!=user.credentialVersion()) {
                    if (request.getSession(false)!=null) request.getSession(false).invalidate();
                    SecurityContextHolder.clearContext(); responses.writeError(ErrorCode.UNAUTHENTICATED,request,response); return;
                }
                var account=versions.getFirst();
                if (Boolean.TRUE.equals(account.get("must_change_password")) && !List.of("/api/auth/me","/api/auth/csrf","/api/auth/password","/api/auth/logout").contains(path)) {
                    responses.writeError(ErrorCode.PASSWORD_CHANGE_REQUIRED,request,response);return;
                }
                if (path.startsWith("/api/admin/") && !Capabilities.forRole((String)account.get("role")).contains(Capabilities.USERS)) {
                    responses.writeError(ErrorCode.FORBIDDEN,request,response);return;
                }
                String expected=request.getHeader("X-Account-ID");
                if (expected!=null && !expected.equals(user.id().toString())) { responses.writeError(ErrorCode.ACCOUNT_CHANGED,request,response); return; }
                response.setHeader("X-Account-ID",user.id().toString());
            }
            chain.doFilter(request,response);
        } catch (org.springframework.dao.DataAccessException e) { responses.writeError(ErrorCode.SERVICE_UNAVAILABLE,request,response); }
    }
}
