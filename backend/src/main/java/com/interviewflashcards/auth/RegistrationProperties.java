package com.interviewflashcards.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.registration")
@org.springframework.validation.annotation.Validated
public record RegistrationProperties(boolean enabled, @org.springframework.boot.context.properties.bind.DefaultValue("100") @jakarta.validation.constraints.Min(1) int maxAccounts) {}
