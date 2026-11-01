package com.interviewflashcards;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationIT {
    @TempDir Path storage;

    @Test
    void emptyPostgresMigrationHealthSecurityAndApplicationRestart() throws Exception {
        String url = requiredEnvironment("DATABASE_URL");
        String username = requiredEnvironment("DATABASE_USERNAME");
        String password = requiredEnvironment("DATABASE_PASSWORD");
        // Every run gets an empty schema, even when the supplied database is reused.
        String schema = "it_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String installation;
            try (var app = start(schema)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                installation = jdbc.queryForObject("select installation_id from application_metadata", String.class);
                assertThat(installation).isNotBlank();
                assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null", Integer.class)).isEqualTo(13);
                assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
                // A data change must survive a complete Spring context/pool restart.
                jdbc.update("update application_metadata set created_at = ?::timestamptz where id = 1", "2026-01-01T00:00:00Z");
                checkHttp(app);
            }
            try (var app = start(schema)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select installation_id from application_metadata", String.class)).isEqualTo(installation);
                assertThat(jdbc.queryForObject("select created_at from application_metadata", java.sql.Timestamp.class).toInstant())
                        .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
                assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version is not null", Integer.class)).isEqualTo(13);
                checkHttp(app);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private ServletWebServerApplicationContext start(String schema) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(FlashcardsApplication.class).run(
                "--server.port=0", "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema, "--spring.datasource.hikari.schema=" + schema,
                "--app.storage.root=" + storage.resolve("files"),
                "--app.account.username=integration",
                "--app.account.password-hash=" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode("integration-only"),
                "--app.storage.temporary-root=" + storage.resolve("tmp"));
    }

    private void checkHttp(ServletWebServerApplicationContext app) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://127.0.0.1:" + app.getWebServer().getPort();
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JsonMapper.builder().build().readTree(response.body());
        assertThat(json.at("/data/status").asText()).isEqualTo("UP");
        assertThat(json.at("/data/learningTimezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(json.at("/meta/requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(json.at("/meta/serverTime").asText()).endsWith("Z");
        var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/api/cards")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(JsonMapper.builder().build().readTree(denied.body()).at("/error/code").asText()).isEqualTo("UNAUTHENTICATED");
        var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(403);
        assertThat(JsonMapper.builder().build().readTree(csrf.body()).at("/error/code").asText()).isEqualTo("CSRF_INVALID");
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        assertThat(value).as("Set %s to a disposable PostgreSQL database before -Pintegration verify", key).isNotNull();
        return value;
    }
}
