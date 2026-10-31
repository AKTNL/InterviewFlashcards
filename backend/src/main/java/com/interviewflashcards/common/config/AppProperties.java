package com.interviewflashcards.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotBlank String learningTimezone, @Valid @NotNull Storage storage) {
    public record Storage(@NotBlank String root, @NotBlank String temporaryRoot) {}
}
