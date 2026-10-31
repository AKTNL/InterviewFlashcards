package com.interviewflashcards.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.CredentialsContainer;

public final class AccountPrincipal implements UserDetails, CredentialsContainer {
    private final UUID id;
    private final String username;
    private String password;
    public AccountPrincipal(UUID id, String username, String password) {
        this.id = id; this.username = username; this.password = password;
    }
    public UUID id() { return id; }
    @Override public void eraseCredentials() { password = null; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public String toString() { return "AccountPrincipal"; }
}
