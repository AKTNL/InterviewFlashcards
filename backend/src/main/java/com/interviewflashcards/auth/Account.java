package com.interviewflashcards.auth;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class Account {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 100) private String username;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(name = "learning_timezone", nullable = false, length = 60) private String learningTimezone;
    @Column(name = "data_epoch", nullable = false) private UUID dataEpoch;
    @Column(nullable = false) private long revision;
    @Column(name = "credential_version", nullable = false) private long credentialVersion;
    private String nickname;
    private String role;
    private boolean enabled;
    @Column(name="must_change_password") private boolean mustChangePassword;
    @Column(name="account_origin", nullable=false) private String accountOrigin;
    public String getRole() { return role; }
    public boolean getEnabled() { return enabled; }
    public boolean getMustChangePassword() { return mustChangePassword; }
    protected Account() {}
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getLearningTimezone() { return learningTimezone; }
    public UUID getDataEpoch() { return dataEpoch; }
    public long getRevision() { return revision; }
    public long getCredentialVersion() { return credentialVersion; }
    public String getNickname() { return nickname; }
    public String getAccountOrigin() { return accountOrigin; }
}
