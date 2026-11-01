package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts = accounts; }
    @Override public UserDetails loadUserByUsername(String username) {
        var account = accounts.findByUsername(username)
                .or(() -> accounts.findByUsername(AccountRules.normalize(username)).filter(a -> java.util.Set.of("REGISTERED","ADMIN_CREATED").contains(a.getAccountOrigin())))
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        if (!account.getEnabled()) throw new UsernameNotFoundException("Account not found");
        return new AccountPrincipal(account.getId(), account.getUsername(), account.getPasswordHash(), account.getCredentialVersion());
    }
    public Profile profile(AccountPrincipal principal) {
        var account = accounts.findById(principal.id()).orElseThrow(() -> new ApiFailure(ErrorCode.UNAUTHENTICATED));
        return new Profile(account.getId(), account.getUsername(), account.getLearningTimezone(),
                account.getDataEpoch(), account.getRevision(), account.getNickname(), account.getRole(), account.getMustChangePassword(),
                Capabilities.forRole(account.getRole()));
    }
    public record Profile(UUID id, String username, String learningTimezone, UUID dataEpoch, long revision, String nickname, String role, boolean mustChangePassword, java.util.Set<String> capabilities) {}
}
