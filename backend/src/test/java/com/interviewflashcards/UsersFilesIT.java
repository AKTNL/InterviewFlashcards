package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.files.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UsersFilesIT {
    @TempDir static Path directory;
    private final String schema = "files_it_" + UUID.randomUUID().toString().replace("-", "");
    private final String hash = new BCryptPasswordEncoder(10).encode("test-only-password");
    private ServletWebServerApplicationContext app;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private byte[] pdf;
    private UUID userId;
    private UUID otherId = UUID.randomUUID();
    private String savedId;

    @BeforeAll void setUp() throws Exception {
        start();
        userId = jdbc.queryForObject("select id from app_user where username='owner'", UUID.class);
        jdbc.update("insert into app_user(id,username,password_hash) values (?,?,?)", otherId, "other", hash);
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(output); pdf = output.toByteArray();
        }
    }
    private void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(FlashcardsApplication.class).run(
                "--server.port=0", "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--spring.datasource.hikari.schema=" + schema, "--app.account.username=owner", "--app.account.password-hash=" + hash,
                "--app.storage.root=" + directory.resolve("files"), "--app.storage.temporary-root=" + directory.resolve("tmp"));
        jdbc = app.getBean(JdbcTemplate.class); mapper = app.getBean(ObjectMapper.class);
        mvc = MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void cleanUp() throws Exception {
        if (app != null) app.close();
        try (var c = DriverManager.getConnection(System.getenv("DATABASE_URL"), System.getenv("DATABASE_USERNAME"), System.getenv("DATABASE_PASSWORD"));
             var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
    private record Login(MockHttpSession session, String token) {}
    private Login login(String username) throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession();
        String token = json(csrf).at("/data/token").asText();
        String oldId = session.getId();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .param("username", username).param("password", "test-only-password"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username));
        assertThat(session.getId()).isNotEqualTo(oldId);
        var fresh = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        return new Login(session, json(fresh).at("/data/token").asText());
    }
    private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    private MvcResult upload(Login login, String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/sources").file(new MockMultipartFile("file", name, "application/pdf", content))
                .session(login.session()).header("X-CSRF-TOKEN", login.token())).andReturn();
    }

    @Test @Order(1) void authenticationCsrfLogoutAndExpiredSession() throws Exception {
        for (String path : List.of("/api/sources", "/api/cards", "/api/backups", "/api/sources/" + UUID.randomUUID() + "/content", "/api/assets/" + UUID.randomUUID() + "/content")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").param("username", "owner").param("password", "test-only-password"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        var csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        mvc.perform(post("/api/auth/login").session((MockHttpSession) csrf.getRequest().getSession())
                .header("X-CSRF-TOKEN", json(csrf).at("/data/token").asText()).param("username", "owner").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
        var desktop = login("owner"); var mobile = login("owner");
        var me = mvc.perform(get("/api/auth/me").session(desktop.session())).andExpect(status().isOk()).andReturn();
        assertThat(json(me).at("/data/id").asText()).isEqualTo(userId.toString());
        assertThat(me.getResponse().getContentAsString()).doesNotContain("password", hash);
        mvc.perform(get("/api/auth/me").session(mobile.session())).andExpect(jsonPath("$.data.id").value(userId.toString()));
        mvc.perform(post("/api/auth/logout").session(desktop.session())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(desktop.session()).header("X-CSRF-TOKEN", desktop.token())).andExpect(status().isOk());
        assertThat(desktop.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(mobile.session())).andExpect(status().isOk());
    }

    @Test @Order(2) void pdfValidationOwnershipRangeAndDuplicateUpload() throws Exception {
        var owner = login("owner"); var other = login("other");
        var first = upload(owner, "../../面试.pdf", pdf);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        savedId = json(first).at("/data/source/id").asText();
        assertThat(json(first).at("/data/duplicate").asBoolean()).isFalse();
        assertThat(json(first).at("/data/source/originalFilename").asText()).isEqualTo("面试.pdf");
        assertThat(first.getResponse().getContentAsString()).doesNotContain("storageKey", directory.toString());
        var second = upload(owner, "renamed.pdf", pdf);
        assertThat(json(second).at("/data/duplicate").asBoolean()).isTrue();
        assertThat(json(second).at("/data/source/id").asText()).isEqualTo(savedId);
        mvc.perform(get("/api/sources/" + savedId + "/content").session(owner.session()).header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes("%PDF-".getBytes()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(head("/api/sources/" + savedId + "/content").session(owner.session()))
                .andExpect(status().isOk()).andExpect(header().longValue("Content-Length", pdf.length));
        mvc.perform(get("/api/sources/" + savedId + "/content").session(owner.session()).header("Range", "bytes=999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        for (String suffix : List.of("", "/content")) {
            mvc.perform(get("/api/sources/" + savedId + suffix).session(other.session())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/sources").session(other.session())).andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":[]")));
        assertThat(upload(owner, "fake.pdf", "not a PDF".getBytes()).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(owner, "empty.pdf", new byte[0]).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(owner, "wrong.txt", pdf).getResponse().getStatus()).isEqualTo(400);
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.protect(new StandardProtectionPolicy("owner-secret", "", new AccessPermission()));
            doc.save(output);
            assertThat(upload(owner, "encrypted.pdf", output.toByteArray()).getResponse().getStatus()).isEqualTo(400);
        }
        try (var files = Files.list(directory.resolve("tmp"))) { assertThat(files).isEmpty(); }
        // Images use the same protected, range-capable storage path. Parsing creates them in P0-05.
        UUID imageId = UUID.randomUUID(); UUID key = UUID.randomUUID();
        byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        Files.write(directory.resolve("files").resolve(key.toString()), image);
        jdbc.update("insert into asset(id,user_id,source_id,storage_key,media_type,byte_size,sha256) values(?,?,?,?,?,?,?)",
                imageId, userId, UUID.fromString(savedId), key, "image/png", image.length, "a".repeat(64));
        mvc.perform(get("/api/assets/" + imageId + "/content").session(owner.session()).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOf(image, 4)));
        mvc.perform(get("/api/assets/" + imageId + "/content").session(other.session())).andExpect(status().isNotFound());
    }

    @Test @Order(3) void databaseOwnershipUniquenessAndConcurrentUploads() throws Exception {
        UUID deck = UUID.randomUUID(); UUID module = UUID.randomUUID(); UUID chapter = UUID.randomUUID(); UUID job = UUID.randomUUID(); UUID draft = UUID.randomUUID(); UUID card = UUID.randomUUID();
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'d')", deck, userId);
        assertThatThrownBy(() -> jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'m','m')", UUID.randomUUID(), otherId, deck))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'m','m')", module, userId, deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'c','c')", chapter, userId, module);
        jdbc.update("insert into import_job(id,user_id,source_id) values(?,?,?)", job, userId, UUID.fromString(savedId));
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'1','q')", draft, userId, chapter, job);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'q')", card, userId, chapter, draft);
        assertThatThrownBy(() -> jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'q')", UUID.randomUUID(), userId, chapter, draft))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (int i = 0; i < 2; i++) {
            int iteration = i;
            var action = org.assertj.core.api.ThrowableAssert.catchThrowable(() -> jdbc.update("insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,latest_rating) values(?,?,?,now(),now(),current_date,'VAGUE')", UUID.randomUUID(), userId, card));
            if (iteration == 0) assertThat(action).isNull(); else assertThat(action).isInstanceOf(DataIntegrityViolationException.class);
        }
        var fileService = app.getBean(SourceService.class);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fileService.upload(otherId, new MockMultipartFile("file", "one.pdf", "application/pdf", pdf)));
            var second = executor.submit(() -> fileService.upload(otherId, new MockMultipartFile("file", "two.pdf", "application/pdf", pdf)));
            assertThat(first.get(20, TimeUnit.SECONDS).source().getId()).isEqualTo(second.get(20, TimeUnit.SECONDS).source().getId());
        }
        assertThat(jdbc.queryForObject("select count(*) from source_document where user_id=?", Integer.class, otherId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from source_document where user_id=?", Integer.class, userId)).isEqualTo(1);
    }

    @Test @Order(4) void filesAndUserIdentitySurviveCompleteApplicationRestart() throws Exception {
        app.close(); start();
        assertThat(jdbc.queryForObject("select id from app_user where username='owner'", UUID.class)).isEqualTo(userId);
        var login = login("owner");
        mvc.perform(get("/api/sources/" + savedId + "/content").session(login.session()))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf));
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version is not null", Integer.class)).isEqualTo(2);
        assertThat(app.getBean(jakarta.servlet.MultipartConfigElement.class).getLocation())
                .isEqualTo(directory.resolve("tmp").toAbsolutePath().normalize().toString());
    }
}
