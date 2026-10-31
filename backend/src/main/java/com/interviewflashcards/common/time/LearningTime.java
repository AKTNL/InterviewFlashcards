package com.interviewflashcards.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public record LearningTime(Clock clock, ZoneId learningZone) {
    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(now(), learningZone);
    }
}
