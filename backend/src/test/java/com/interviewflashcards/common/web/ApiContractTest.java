package com.interviewflashcards.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.health.HealthController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class ApiContractTest {
    private MockMvc mvc;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var time = new LearningTime(Clock.fixed(Instant.parse("2026-10-30T16:00:00Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Shanghai"));
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var responses = new ApiResponses(time, mapper);
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(jdbc, time, responses), new TestController())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new ApiExceptionHandler(responses))
                .addFilters(new RequestTraceFilter()).build();
    }

    @Test
    void healthHasDatabaseCheckUtcTimeAndServerGeneratedTrace() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(UUID.randomUUID().toString());
        var response = mvc.perform(get("/api/health").header("X-Request-ID", "forged-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database").value("UP"))
                .andExpect(jsonPath("$.data.learningDate").value("2026-10-31"))
                .andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        String requestId = response.getHeader("X-Request-ID");
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(body.at("/meta/requestId").asText()).isEqualTo(requestId);
        assertThat(Instant.parse(body.at("/meta/serverTime").asText())).isEqualTo(Instant.parse("2026-10-30T16:00:00Z"));
    }

    @Test
    void unavailableDatabaseIs503WithoutConnectionDetails(CapturedOutput output) throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("private-db-password"));
        mvc.perform(get("/api/health?secret=private-query"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
        assertThat(output.getAll()).doesNotContain("private-db-password", "private-query");
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }

    @Test
    void validationAndMalformedJsonNeverEchoInput() throws Exception {
        var response = mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"secret\":\"private-body\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields[0]").value("name")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private-body");
        mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void unexpectedErrorsAreSanitized(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertThat(output.getAll()).doesNotContain("private-exception-content");
    }

    @RestController
    static class TestController {
        record Input(@NotBlank String name, String secret) {}

        @PostMapping("/test/input")
        String input(@Valid @RequestBody Input input) { return "ok"; }

        @GetMapping("/test/fail")
        String fail() { throw new IllegalStateException("private-exception-content"); }
    }
}
