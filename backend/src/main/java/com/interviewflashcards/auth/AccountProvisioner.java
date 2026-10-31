package com.interviewflashcards.auth;

import com.interviewflashcards.common.config.AppProperties;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountProvisioner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    public AccountProvisioner(JdbcTemplate jdbc, AppProperties properties) { this.jdbc = jdbc; this.properties = properties; }
    @Override @Transactional public void run(ApplicationArguments args) {
        var account = properties.account();
        if (account.username().length() > 100 || !account.username().equals(account.username().strip())
                || !account.passwordHash().matches("\\$2[aby]\\$(1[0-6])\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalStateException("Configure ACCOUNT_USERNAME and a BCrypt hash with cost 10..16");
        }
        // Rotating the configured hash never replaces the user's data identity.
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, learning_timezone)
                VALUES (?, ?, ?, ?) ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash
                """, UUID.randomUUID(), account.username(), account.passwordHash(), properties.learningTimezone());
    }
}
