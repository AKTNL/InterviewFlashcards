package com.interviewflashcards.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.registration")
public record RegistrationProperties(boolean enabled) {}
