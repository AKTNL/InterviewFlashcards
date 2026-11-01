package com.interviewflashcards.auth;

import java.util.Set;
/** Stable, deny-by-default capability names. Invitation grants remain separate and quota-controlled. */
public final class Capabilities {
    public static final String USERS="users.manage", INVITATIONS="invitations.manage", LIBRARY="library.manage", DASHBOARD="dashboard.read";
    public static Set<String> forRole(String role) {
        return "ADMIN".equals(role)?Set.of(USERS,INVITATIONS,LIBRARY,DASHBOARD):Set.of();
    }
    private Capabilities() {}
}
