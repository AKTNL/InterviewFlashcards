package com.interviewflashcards.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotBlank String learningTimezone, @Valid @NotNull Storage storage,
                            @Valid @NotNull Account account) {
    public record Storage(@NotBlank String root, @NotBlank String temporaryRoot, @Positive long maxBytes) {}
    public record Account(@NotBlank String username, @NotBlank String passwordHash) {}
}
