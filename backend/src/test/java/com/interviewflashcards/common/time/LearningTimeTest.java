package com.interviewflashcards.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LearningTimeTest {
    @Test
    void shanghaiLearningDayChangesAtSixteenUtcRegardlessOfHostTimezone() {
        var zone = ZoneId.of("Asia/Shanghai");
        var before = new LearningTime(Clock.fixed(Instant.parse("2026-10-30T15:59:59Z"), ZoneOffset.UTC), zone);
        var after = new LearningTime(Clock.fixed(Instant.parse("2026-10-30T16:00:00Z"), ZoneId.of("America/New_York")), zone);
        assertThat(before.today()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(after.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(after.now()).isEqualTo(Instant.parse("2026-10-30T16:00:00Z"));
    }
}
