package com.interviewflashcards.health;

import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public record HealthController(JdbcTemplate jdbc, LearningTime time, ApiResponses responses) {
    @GetMapping("/api/health")
    public ApiResponse<Health> health(HttpServletRequest request) {
        // Also checks the Flyway-created baseline, rather than returning a static UP.
        jdbc.queryForObject("select installation_id from application_metadata where id = 1", String.class);
        return responses.success(new Health("UP", "UP", time.learningZone().getId(), time.today()), request);
    }

    public record Health(String status, String database, String learningTimezone, LocalDate learningDate) {}
}
