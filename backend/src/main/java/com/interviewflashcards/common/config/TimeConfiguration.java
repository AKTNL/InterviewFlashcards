package com.interviewflashcards.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId learningZone(AppProperties properties) {
        return ZoneId.of(properties.learningTimezone());
    }
}
