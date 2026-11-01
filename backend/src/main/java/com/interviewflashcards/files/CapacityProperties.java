package com.interviewflashcards.files;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.capacity")
public record CapacityProperties(@DefaultValue("536870912") @Positive long userBytes,
    @DefaultValue("20") @Positive int sources, @DefaultValue("2") @Positive int imports,
    @DefaultValue("2") @Positive int backups, @DefaultValue("20") @Positive int globalTasks,
    @DefaultValue("5368709120") @Positive long globalBytes, @DefaultValue("536870912") @Positive long reserveBytes) {}
