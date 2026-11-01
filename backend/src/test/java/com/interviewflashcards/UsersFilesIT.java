package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.files.*;
import com.interviewflashcards.importing.*;
import com.interviewflashcards.sync.MutationService;
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
                "--server.port=0", "--app.import.poll-ms=3600000", "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
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
        return upload(login, name, content, UUID.randomUUID(), epoch(login));
    }
    private UUID epoch(Login login) throws Exception {
        return UUID.fromString(json(mvc.perform(get("/api/auth/me").session(login.session())).andReturn()).at("/data/dataEpoch").asText());
    }
    private MvcResult upload(Login login, String name, byte[] content, UUID operation, UUID epoch) throws Exception {
        return mvc.perform(multipart("/api/sources").file(new MockMultipartFile("file", name, "application/pdf", content))
                .param("operationId", operation.toString()).param("dataEpoch", epoch.toString())
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
        job = jdbc.queryForObject("select id from import_job where user_id=? and source_id=?", UUID.class, userId, UUID.fromString(savedId));
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
        UUID otherEpoch = jdbc.queryForObject("select data_epoch from app_user where id=?", UUID.class, otherId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fileService.upload(otherId, new MutationService.Command(UUID.randomUUID(), otherEpoch), new MockMultipartFile("file", "one.pdf", "application/pdf", pdf)));
            var second = executor.submit(() -> fileService.upload(otherId, new MutationService.Command(UUID.randomUUID(), otherEpoch), new MockMultipartFile("file", "two.pdf", "application/pdf", pdf)));
            assertThat(first.get(20, TimeUnit.SECONDS).at("/source/id")).isEqualTo(second.get(20, TimeUnit.SECONDS).at("/source/id"));
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
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version is not null", Integer.class)).isEqualTo(13);
        assertThat(app.getBean(jakarta.servlet.MultipartConfigElement.class).getLocation())
                .isEqualTo(directory.resolve("tmp").toAbsolutePath().normalize().toString());
    }
    private MvcResult rename(Login login, String source, UUID operation, UUID epoch, long version, String name) throws Exception {
        return mvc.perform(patch("/api/sources/" + source).session(login.session()).header("X-CSRF-TOKEN", login.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("operationId", operation, "dataEpoch", epoch,
                        "expectedVersion", version, "name", name)))).andReturn();
    }
    private long revision() { return jdbc.queryForObject("select revision from app_user where id=?", Long.class, userId); }

    @Test @Order(5) void lostResponseReplayDigestAndReceiptOwnership() throws Exception {
        var owner = login("owner"); var other = login("other"); UUID epoch = epoch(owner); UUID operation = UUID.randomUUID();
        long before = revision();
        var first = rename(owner, savedId, operation, epoch, 0, "更新资料.pdf");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(first).at("/data/source/version").asLong()).isEqualTo(1);
        assertThat(json(first).at("/data/mutation/revision").asLong()).isEqualTo(before + 1);
        var replay = rename(owner, savedId, operation, epoch, 0, "更新资料.pdf");
        assertThat(json(replay).get("data")).isEqualTo(json(first).get("data"));
        assertThat(revision()).isEqualTo(before + 1);
        var changed = rename(owner, savedId, operation, epoch, 1, "不同内容.pdf");
        assertThat(json(changed).at("/error/code").asText()).isEqualTo("OPERATION_ID_REUSED");
        mvc.perform(get("/api/sync/receipts/" + operation).param("dataEpoch", epoch.toString()).session(owner.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source.originalFilename").value("更新资料.pdf"));
        mvc.perform(get("/api/sync/receipts/" + operation).param("dataEpoch", epoch(other).toString()).session(other.session()))
                .andExpect(status().isNotFound());
        UUID uploadId = UUID.randomUUID();
        var upload = upload(owner, "retry.pdf", pdf, uploadId, epoch);
        assertThat(json(upload(owner, "retry.pdf", pdf, uploadId, epoch)).get("data")).isEqualTo(json(upload).get("data"));
        assertThat(json(upload(owner, "other-name.pdf", pdf, uploadId, epoch)).at("/error/code").asText()).isEqualTo("OPERATION_ID_REUSED");
        assertThat(revision()).isEqualTo(before + 1);
        mvc.perform(multipart("/api/sources").file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf))
                .session(owner.session()).header("X-CSRF-TOKEN", owner.token())).andExpect(status().isBadRequest());
    }

    @Test @Order(6) void twoDevicesCannotOverwriteTheSameVersionAndSnapshotsMatch() throws Exception {
        var desktop = login("owner"); var phone = login("owner"); UUID epoch = epoch(desktop);
        long before = revision(); var barrier = new CyclicBarrier(2);
        List<MvcResult> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(); return rename(desktop, savedId, UUID.randomUUID(), epoch, 1, "电脑.pdf"); });
            var second = executor.submit(() -> { barrier.await(); return rename(phone, savedId, UUID.randomUUID(), epoch, 1, "手机.pdf"); });
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
        var conflict = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(json(conflict).at("/error/code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(json(conflict).at("/error/details/current/version").asLong()).isEqualTo(2);
        assertThat(revision()).isEqualTo(before + 1);
        var snapshot = json(mvc.perform(get("/api/sync/snapshot").session(phone.session())).andExpect(status().isOk()).andReturn());
        assertThat(snapshot.at("/data/sources/0/version").asLong()).isEqualTo(2);
        assertThat(snapshot.at("/data/revision").asLong()).isEqualTo(revision());
        mvc.perform(get("/api/sync/revision")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sync/revision").session(phone.session())).andExpect(jsonPath("$.data.dataEpoch").value(epoch.toString()));
        var other = login("other");
        assertThat(rename(other, savedId, UUID.randomUUID(), epoch(other), 2, "越权.pdf").getResponse().getStatus()).isEqualTo(404);
        assertThat(rename(phone, savedId, UUID.randomUUID(), epoch, 2, " ").getResponse().getStatus()).isEqualTo(400);
    }

    @Test @Order(7) void callbackAndReceiptFailureRollbackAndConcurrentSameIdOnlyExecuteOnce() throws Exception {
        var mutations = app.getBean(MutationService.class); var owner = login("owner"); UUID epoch = epoch(owner);
        UUID failedId = UUID.randomUUID(); UUID deckId = UUID.randomUUID(); long before = revision();
        assertThatThrownBy(() -> mutations.execute(userId, new MutationService.Command(failedId, epoch), "test.rollback", Map.of(), () -> {
            jdbc.update("insert into deck(id,user_id,name) values(?,?,'rollback')", deckId, userId);
            throw new java.io.IOException("simulated storage failure");
        })).isInstanceOf(java.io.IOException.class);
        assertThat(jdbc.queryForObject("select count(*) from deck where id=?", Integer.class, deckId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mutation_receipt where operation_id=?", Integer.class, failedId)).isZero();
        assertThat(revision()).isEqualTo(before);
        UUID receiptFailure = UUID.randomUUID();
        jdbc.execute("create function reject_test_receipt() returns trigger language plpgsql as $$ begin if NEW.operation_id='" + receiptFailure + "'::uuid then raise exception 'test receipt failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_test_receipt before insert on mutation_receipt for each row execute function reject_test_receipt()");
        try {
            var failed = rename(owner, savedId, receiptFailure, epoch, 2, "必须回滚.pdf");
            assertThat(failed.getResponse().getStatus()).isEqualTo(503);
            assertThat(jdbc.queryForObject("select version from source_document where id=?", Long.class, UUID.fromString(savedId))).isEqualTo(2);
            assertThat(revision()).isEqualTo(before);
        } finally { jdbc.execute("drop trigger reject_test_receipt on mutation_receipt"); jdbc.execute("drop function reject_test_receipt()"); }
        UUID operation = UUID.randomUUID(); var calls = new java.util.concurrent.atomic.AtomicInteger(); var barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<JsonNode> action = () -> { barrier.await(); return mutations.execute(userId,
                new MutationService.Command(operation, epoch), "test.concurrent", Map.of("a", 1), () -> {
                    calls.incrementAndGet(); return new MutationService.Change(Map.of("ok", true), 0, true);
                }); };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(action); var b = executor.submit(action);
            assertThat(mapper.readTree(a.get(20, TimeUnit.SECONDS).toString())).isEqualTo(mapper.readTree(b.get(20, TimeUnit.SECONDS).toString()));
        }
        assertThat(calls).hasValue(1); assertThat(revision()).isEqualTo(before + 1);
    }

    @Test @Order(8) void epochChangeRejectsEvenExistingReceiptsAndOldWrites() throws Exception {
        var owner = login("owner"); UUID oldEpoch = epoch(owner); UUID operation = UUID.randomUUID();
        assertThat(rename(owner, savedId, operation, oldEpoch, 2, "恢复前.pdf").getResponse().getStatus()).isEqualTo(200);
        UUID newEpoch = UUID.randomUUID();
        jdbc.update("update app_user set data_epoch=?,revision=revision+1 where id=?", newEpoch, userId);
        long before = revision();
        var replay = rename(owner, savedId, operation, oldEpoch, 2, "恢复前.pdf");
        assertThat(json(replay).at("/error/code").asText()).isEqualTo("EPOCH_EXPIRED");
        assertThat(json(replay).at("/error/details/dataEpoch").asText()).isEqualTo(newEpoch.toString());
        assertThat(json(upload(owner, "old.pdf", pdf, UUID.randomUUID(), oldEpoch)).at("/error/code").asText()).isEqualTo("EPOCH_EXPIRED");
        mvc.perform(get("/api/sync/receipts/" + operation).param("dataEpoch", oldEpoch.toString()).session(owner.session()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.dataEpoch").value(newEpoch.toString()));
        assertThat(revision()).isEqualTo(before);
    }

    private UUID realJob;
    private UUID realSource;
    private ImportService.Ticket completedTicket;
    private MvcResult retryJob(Login login,UUID id,UUID operation,UUID epoch,long version) throws Exception {
        return mvc.perform(post("/api/import-jobs/"+id+"/retry").session(login.session()).header("X-CSRF-TOKEN",login.token())
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("operationId",operation,"dataEpoch",epoch,"expectedVersion",version)))).andReturn();
    }
    @Test @Order(9) void realPdfUploadCreatesOnePersistentTaskAndCompleteOwnedDrafts() throws Exception {
        var owner=login("owner"); var other=login("other");
        byte[] real=Files.readAllBytes(Path.of("../docs/Java+agent八股(高频版).pdf"));
        var result=json(upload(owner,"当前资料.pdf",real));
        realJob=UUID.fromString(result.at("/data/importJobId").asText()); realSource=UUID.fromString(result.at("/data/source/id").asText());
        assertThat(json(upload(owner,"重复.pdf",real)).at("/data/importJobId").asText()).isEqualTo(realJob.toString());
        var imports=app.getBean(ImportService.class);
        assertThat(imports.job(userId,realJob).get("status")).isEqualTo("QUEUED");
        completedTicket=imports.claim(realJob,userId);
        app.getBean(ImportWorker.class).run(completedTicket);
        var job=imports.job(userId,realJob);
        assertThat(job.get("status")).isEqualTo("READY");
        assertThat(((JsonNode)job.get("report")).path("candidateCount").asInt()).isEqualTo(158);
        assertThat(imports.chapters(userId,realJob)).hasSize(13);
        assertThat(jdbc.queryForObject("select count(*) from draft_card where import_job_id=?",Integer.class,realJob)).isEqualTo(158);
        assertThat(jdbc.queryForObject("select count(*) from card where draft_id in (select id from draft_card where import_job_id=?)",Integer.class,realJob)).isZero();
        var redis=imports.chapters(userId,realJob).stream().filter(c->"Redis".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(redis.get("originalName")).isEqualTo("Reids篇");
        UUID draft=jdbc.queryForObject("select id from draft_card where import_job_id=? and question like '%HashMap%扩容%'",UUID.class,realJob);
        var detail=imports.draft(userId,draft);
        assertThat((List<?>)detail.get("followups")).hasSize(4);
        assertThat((List<?>)detail.get("sources")).hasSize(3);
        assertThat(detail.get("originalContent")).isEqualTo(detail.get("contentBlocks"));
        UUID chapter=jdbc.queryForObject("select chapter_id from draft_card where id=?",UUID.class,draft);
        for(String path:List.of("/api/import-jobs/"+realJob,"/api/import-jobs/"+realJob+"/chapters","/api/chapters/"+chapter+"/drafts","/api/drafts/"+draft,"/api/sources/"+realSource+"/pages/44/image")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).session(other.session())).andExpect(status().isNotFound());
            mvc.perform(get(path).session(owner.session())).andExpect(status().isOk());
        }
        mvc.perform(get("/api/chapters/"+chapter+"/drafts").param("limit","1000").session(owner.session())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sources/"+realSource+"/pages/0/image").session(owner.session())).andExpect(status().isNotFound());
        assertThat(json(retryJob(other,realJob,UUID.randomUUID(),epoch(other),0)).at("/error/code").asText()).isEqualTo("NOT_FOUND");
    }
    @Test @Order(10) void readyTaskCannotReparseAndExistingEditsAndConfirmedCardsAreProtected() throws Exception {
        var owner=login("owner"); var imports=app.getBean(ImportService.class);
        long version=((Number)imports.job(userId,realJob).get("version")).longValue();
        assertThat(json(retryJob(owner,realJob,UUID.randomUUID(),epoch(owner),version)).at("/error/code").asText()).isEqualTo("IMPORT_NOT_RETRYABLE");
        UUID draft=jdbc.queryForObject("select id from draft_card where import_job_id=? order by sort_order limit 1",UUID.class,realJob);
        UUID chapter=jdbc.queryForObject("select chapter_id from draft_card where id=?",UUID.class,draft);
        jdbc.update("update draft_card set question='manual edit',version=1 where id=?",draft);
        jdbc.update("update chapter set check_status='IMPORTED',version=1 where id=?",chapter);
        UUID card=UUID.randomUUID(); jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'confirmed')",card,userId,chapter,draft);
        jdbc.update("update import_job set status='FAILED' where id=?",realJob);
        assertThat(json(retryJob(owner,realJob,UUID.randomUUID(),epoch(owner),version-1)).at("/error/code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(json(retryJob(owner,realJob,UUID.randomUUID(),epoch(owner),version)).at("/error/code").asText()).isEqualTo("IMPORT_CONTENT_EXISTS");
        app.getBean(ImportWorker.class).run(completedTicket);
        assertThat(imports.draft(userId,draft).get("question")).isEqualTo("manual edit");
        assertThat(jdbc.queryForObject("select count(*) from card where id=?",Integer.class,card)).isEqualTo(1);
        jdbc.update("update import_job set status='READY' where id=?",realJob);
    }
    @Test @Order(11) void partialPersistenceRollsBackAndRetryResumesEvidenceIdempotently() throws Exception {
        var other=login("other"); var imports=app.getBean(ImportService.class);
        var upload=json(upload(other,"retry-real.pdf",Files.readAllBytes(Path.of("../docs/Java+agent八股(高频版).pdf"))));
        UUID id=UUID.fromString(upload.at("/data/importJobId").asText());
        var ticket=imports.claim(id,otherId);
        var raw=mapper.readTree(jdbc.queryForObject("select raw_evidence::text from import_job where id=?",String.class,realJob));
        imports.checkpoint(ticket,"PERSISTING",90,raw);
        // Fail after one candidate has been inserted: deck, chapters, spans and drafts must all roll back.
        jdbc.execute("create function reject_test_draft() returns trigger language plpgsql as $$ begin if NEW.import_job_id='"+id+"'::uuid and NEW.source_entry='q002' then raise exception 'injected persistence failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_test_draft before insert on draft_card for each row execute function reject_test_draft()");
        try { app.getBean(ImportWorker.class).run(new ImportService.Ticket(ticket.id(),ticket.user(),ticket.epoch(),ticket.token(),ticket.source(),ticket.storageKey(),ticket.filename(),raw)); }
        finally { jdbc.execute("drop trigger reject_test_draft on draft_card"); jdbc.execute("drop function reject_test_draft()"); }
        assertThat(imports.job(otherId,id).get("status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from draft_card where import_job_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from deck where user_id=?",Integer.class,otherId)).isZero();
        long version=((Number)imports.job(otherId,id).get("version")).longValue(); UUID operation=UUID.randomUUID();
        var first=json(retryJob(other,id,operation,epoch(other),version)).get("data");
        assertThat(json(retryJob(other,id,UUID.randomUUID(),epoch(other),version)).at("/error/code").asText()).isEqualTo("VERSION_CONFLICT");
        var retry=imports.claim(id,otherId); assertThat(retry.raw()).isNotNull();
        Path file=directory.resolve("files").resolve(ticket.storageKey().toString()), hidden=file.resolveSibling(file.getFileName()+".hidden");
        Files.move(file,hidden);
        try { app.getBean(ImportWorker.class).run(retry); } finally { Files.move(hidden,file); }
        assertThat(imports.job(otherId,id).get("status")).isEqualTo("READY");
        assertThat(json(retryJob(other,id,operation,epoch(other),version)).get("data")).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from draft_card where import_job_id=?",Integer.class,id)).isEqualTo(158);
        assertThat(jdbc.queryForObject("select count(*) from deck where user_id=?",Integer.class,otherId)).isEqualTo(1);
    }
    @Test @Order(12) void restartMarksInterruptedTaskAndUnreadableTextFailsWithReason() throws Exception {
        var owner=login("owner"); var imports=app.getBean(ImportService.class);
        byte[] blank;
        try(var doc=new PDDocument(); var output=new ByteArrayOutputStream()) { doc.addPage(new PDPage());doc.addPage(new PDPage());doc.save(output);blank=output.toByteArray(); }
        var result=json(upload(owner,"scanned.pdf",blank)); UUID id=UUID.fromString(result.at("/data/importJobId").asText());
        var interrupted=imports.claim(id,userId);
        imports.checkpoint(interrupted,"EXTRACTING",25,null);
        app.close();start();imports=app.getBean(ImportService.class);owner=login("owner");
        var job=imports.job(userId,id); assertThat(job.get("status")).isEqualTo("FAILED");assertThat(job.get("errorCode")).isEqualTo("INTERRUPTED");assertThat(job.get("progress")).isEqualTo(25);
        long version=((Number)job.get("version")).longValue();
        assertThat(json(retryJob(owner,id,UUID.randomUUID(),UUID.randomUUID(),version)).at("/error/code").asText()).isEqualTo("EPOCH_EXPIRED");
        assertThat(retryJob(owner,id,UUID.randomUUID(),epoch(owner),version).getResponse().getStatus()).isEqualTo(200);
        app.getBean(ImportWorker.class).run(imports.claim(id,userId));
        job=imports.job(userId,id); assertThat(job.get("status")).isEqualTo("FAILED");assertThat(job.get("errorCode")).isEqualTo("INSUFFICIENT_TEXT");assertThat(job.get("errorMessage").toString()).contains("重新选择");
        assertThat(jdbc.queryForObject("select count(*) from draft_card where import_job_id=?",Integer.class,realJob)).isEqualTo(158);
        assertThat(imports.job(userId,realJob).get("status")).isEqualTo("READY");
        // A worker from before restart cannot publish into a newer attempt.
        assertThat(imports.checkpoint(interrupted,"PERSISTING",90,null)).isFalse();
    }
}
