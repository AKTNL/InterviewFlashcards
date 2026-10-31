package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.backup.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.interviewflashcards.learning.*;
import java.io.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupIT {
    @TempDir static Path directory;
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-09-01T04:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @TestConfiguration static class TimeOverride { @Bean @Primary TestClock backupTestClock(){return new TestClock();} }
    private final String schema="backup_it_"+UUID.randomUUID().toString().replace("-","");
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc; private ObjectMapper mapper; private BackupService backups; private FileStorage storage; private LearningService learning; private MockMvc mvc; private TestClock clock;
    private UUID user,epoch,card,chapter,source;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class,TimeOverride.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,
            "--spring.datasource.hikari.schema="+schema,"--app.account.username=backup-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);backups=app.getBean(BackupService.class);storage=app.getBean(FileStorage.class);learning=app.getBean(LearningService.class);clock=app.getBean(TestClock.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    private UUID account() {
        UUID id=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'credential-must-not-export')",id,"u-"+id);return id;
    }
    private UUID publish(byte[] bytes) throws Exception {
        UUID key=UUID.randomUUID();try(var staged=storage.stage(new ByteArrayInputStream(bytes))){storage.publish(staged,key);}return key;
    }
    @BeforeEach void seed() throws Exception {
        clock.value=Instant.parse("2026-09-01T04:00:00Z");user=account();epoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user);
        source=UUID.randomUUID();UUID job=UUID.randomUUID(),deck=UUID.randomUUID(),module=UUID.randomUUID();chapter=UUID.randomUUID();card=UUID.randomUUID();
        byte[] pdf;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.addPage(new PDPage());doc.save(out);pdf=out.toByteArray();}
        UUID pdfKey=publish(pdf);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'source.pdf',?,2,?,?)",source,user,BackupArchive.hash(storage.read(pdfKey).getFile().toPath()),pdf.length,pdfKey);
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",deck,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,user,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'Java','Java','IMPORTED')",chapter,user,module);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id,report) values(?,?,?,'READY',?,'{\"evidence\":\"saved\"}')",job,user,source,deck);
        UUID firstDraft=UUID.randomUUID(),draft=UUID.randomUUID();
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'q1','正式题')",firstDraft,user,chapter,job);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,parent_draft_id) values(?,?,?,?,'q2','未确认修改',?)",draft,user,chapter,job,firstDraft);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question,needs_check) values(?,?,?,?,'正式题',true)",card,user,chapter,firstDraft);
        jdbc.update("insert into chapter_check_progress(id,user_id,chapter_id,current_draft_id,status) values(?,?,?,?,'IN_PROGRESS')",UUID.randomUUID(),user,chapter,draft);
        jdbc.update("insert into draft_edit_operation(id,user_id,chapter_id,operation_type,input_ids,output_ids,reason) values(?,?,?,'EDIT',?::jsonb,?::jsonb,'保留调整原因')",UUID.randomUUID(),user,chapter,"[\""+draft+"\"]","[\""+draft+"\"]");
        jdbc.update("insert into personal_annotation(id,user_id,card_id,notes,hint,memory_points,favorite) values(?,?,?,'原笔记','提示','要点',true)",UUID.randomUUID(),user,card);
        jdbc.update("insert into learning_preference(id,user_id,daily_new_count,review_batch_size) values(?,?,0,3)",UUID.randomUUID(),user);
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);byte[] png=out.toByteArray();UUID imageKey=publish(png),asset=UUID.randomUUID();
        jdbc.update("insert into asset(id,user_id,source_id,storage_key,media_type,byte_size,sha256) values(?,?,?,?,'image/png',?,?)",asset,user,source,imageKey,png.length,BackupArchive.hash(storage.read(imageKey).getFile().toPath()));
        jdbc.update("insert into source_span(id,user_id,source_id,draft_id,asset_id,physical_page) values(?,?,?,?,?,2)",UUID.randomUUID(),user,source,draft,asset);
        var session=learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,List.of(card))).get("session");
        var scored=rate(session,"REMEMBERED").get("session");
        var undo=mapper.createObjectNode().put("eventId",scored.at("/events/0/id").asText());
        var undone=learning.write(user,UUID.fromString(session.path("id").asText()),new LearningController.Write(UUID.randomUUID(),epoch,scored.path("version").asLong(),"UNDO",undo)).get("session");
        rate(undone,"FORGOT"); // Includes the revoked event, compensation, effective state and replay allowance.
        clock.value=Instant.parse("2026-10-31T04:00:00Z");
    }
    private JsonNode rate(JsonNode session,String rating) throws Exception {
        var p=mapper.createObjectNode().put("itemId",session.at("/current/id").asText()).put("expectedReviewVersion",session.at("/current/expectedVersion").asLong()).put("answerRevealed",true).put("confirmEarly",true).put("rating",rating);
        return learning.write(user,UUID.fromString(session.path("id").asText()),new LearningController.Write(UUID.randomUUID(),epoch,session.path("version").asLong(),"RATE",p));
    }
    private UUID export() throws Exception {
        UUID operation=UUID.randomUUID();var job=backups.createExport(user,operation);UUID id=UUID.fromString(job.path("id").asText());
        assertThat(backups.createExport(user,operation)).isEqualTo(job);backups.export(user,id);return id;
    }
    private UUID upload(UUID target,byte[] bytes) throws Exception {
        return UUID.fromString(backups.upload(target,UUID.randomUUID(),new MockMultipartFile("file","backup.zip","application/zip",bytes)).path("id").asText());
    }
    private UUID preflight(UUID target,UUID export) throws Exception {
        UUID id=upload(target,backups.download(user,export).getContentAsByteArray());backups.validate(target,id);return id;
    }
    private JsonNode confirm(UUID target,UUID id) {
        var job=backups.job(target,id,true);var p=job.path("validation_result");return backups.confirm(target,id,UUID.fromString(p.path("expectedEpoch").asText()),p.path("expectedRevision").asLong(),true);
    }
    @Test void completeRoundTripKeepsOverdueDatesResourcesHistoryAndIsIdempotent() throws Exception {
        learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,List.of(card)));
        UUID export=export();var oldState=jdbc.queryForMap("select due_date,stage,lapse_count from review_state where user_id=?",user);
        var oldFiles=jdbc.queryForList("select storage_key from source_document where user_id=?",UUID.class,user);
        jdbc.update("update personal_annotation set notes='继续学习后的修改' where user_id=?",user);jdbc.update("update app_user set revision=revision+1 where id=?",user);
        UUID id=preflight(user,export);confirm(user,id);backups.restore(user,id);
        assertThat(backups.job(user,id,true).path("status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForMap("select due_date,stage,lapse_count from review_state where user_id=?",user)).isEqualTo(oldState);
        assertThat(oldState.get("due_date").toString()).isEqualTo("2026-09-02");
        assertThat(jdbc.queryForObject("select notes from personal_annotation where user_id=?",String.class,user)).isEqualTo("原笔记");
        assertThat(jdbc.queryForObject("select count(*) from review_event where user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from review_replay_allowance where user_id=?",Long.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select question from draft_card where user_id=? and source_entry='q2'",String.class,user)).isEqualTo("未确认修改");
        assertThat(jdbc.queryForObject("select favorite from personal_annotation where user_id=?",Boolean.class,user)).isTrue();
        UUID newEpoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user);assertThat(newEpoch).isNotEqualTo(epoch);
        assertThat(confirm(user,id).path("status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user)).isEqualTo(newEpoch);
        UUID newKey=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,user);assertThat(oldFiles).doesNotContain(newKey);
        assertThat(storage.read(newKey).getContentAsByteArray()).isEqualTo(storage.read(oldFiles.getFirst()).getContentAsByteArray());
        assertThat(jdbc.queryForObject("select password_hash from app_user where id=?",String.class,user)).isEqualTo("credential-must-not-export");
        var principal=new AccountPrincipal(user,"test","unused");
        mvc.perform(post("/api/learning-sessions").with(user(principal)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("operationId",UUID.randomUUID(),"dataEpoch",epoch,"cardIds",List.of(card)))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EPOCH_EXPIRED"));
        UUID reExport=export();assertThat(backups.job(user,reExport,false).path("manifest").path("counts").path("review_event").asInt()).isEqualTo(3);
        UUID resumedId=jdbc.queryForObject("select id from learning_session where user_id=? and learning_date='2026-10-31'",UUID.class,user);
        var resumed=mapper.valueToTree(learning.session(user,resumedId));assertThat(resumed.path("current").isObject()).isTrue();
        epoch=newEpoch;assertThat(rate(resumed,"REMEMBERED").has("eventId")).isTrue();
    }
    @Test void restoreToAnotherAccountNeverChangesOwnershipOrCredentialsAndRemapsAllLinks() throws Exception {
        UUID export=export(),target=account();
        byte[] forgedOwnership=changed(export,d->d.elements().forEachRemaining(rows->rows.forEach(r->((ObjectNode)r).put("user_id",user.toString()))));
        UUID id=upload(target,forgedOwnership);backups.validate(target,id);confirm(target,id);backups.restore(target,id);
        assertThat(jdbc.queryForObject("select count(*) from card where user_id=?",Long.class,target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from card where user_id=?",UUID.class,target)).isNotEqualTo(card);
        assertThat(jdbc.queryForObject("select count(*) from card where user_id=?",Long.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from app_user where id=?",String.class,target)).isEqualTo("credential-must-not-export");
        mvc.perform(get("/api/backups/"+export+"/download").with(user(new AccountPrincipal(target,"other","unused")))).andExpect(status().isNotFound());
        mvc.perform(post("/api/restores/"+id+"/confirm").with(user(new AccountPrincipal(user,"original","unused"))).with(csrf()).contentType("application/json").content("{\"dataEpoch\":\""+epoch+"\",\"expectedRevision\":0,\"confirmed\":true}"))
            .andExpect(status().isNotFound());
    }
    private byte[] changed(UUID export, Consumer<ObjectNode> change) throws Exception {
        Path dir=Files.createTempDirectory(directory,"change-");try {
            Path input=dir.resolve("in.zip");Files.write(input,backups.download(user,export).getContentAsByteArray());Path unpack=Files.createDirectory(dir.resolve("files"));
            var opened=BackupArchive.read(input,unpack,mapper);change.accept(opened.data());mapper.writeValue(opened.files().get("data.json").toFile(),opened.data());
            var files=new LinkedHashMap<>(opened.files());files.remove("manifest.json");Path output=dir.resolve("out.zip");BackupArchive.write(output,opened.manifest(),files,mapper);return Files.readAllBytes(output);
        } finally { BackupArchive.cleanup(dir); }
    }
    @Test void invalidRelationsAndStateAreRejectedBeforeConfirmationWithoutChangingCurrentSpace() throws Exception {
        UUID export=export();long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(
            d->((ObjectNode)d.path("card").get(0)).put("chapter_id",UUID.randomUUID().toString()),
            d->((ObjectNode)d.path("source_span").get(0)).put("physical_page",999),
            d->((ObjectNode)d.path("review_state").get(0)).put("due_date","2099-01-01"),
            d->((ObjectNode)d.path("review_event").get(0)).put("card_id",UUID.randomUUID().toString())
        )) {
            UUID id=upload(user,changed(export,change));assertThatThrownBy(()->backups.validate(user,id)).isInstanceOf(Exception.class);
            assertThat(backups.job(user,id,true).path("status").asText()).isNotEqualTo("AWAITING_CONFIRMATION");
        }
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("select id from card where user_id=?",UUID.class,user)).isEqualTo(card);
    }
    @Test void concurrentChangeRequiresNewPreviewAndDatabaseFailureRollsBackAllReplacement() throws Exception {
        UUID id=preflight(user,export());jdbc.update("update app_user set revision=revision+1 where id=?",user);
        assertThatThrownBy(()->confirm(user,id)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.RESTORE_STALE));
        backups.preview(user,id);confirm(user,id);
        jdbc.execute("create function reject_backup_card() returns trigger language plpgsql as $$ begin raise exception 'injected restore failure'; end $$");
        jdbc.execute("create trigger reject_backup_card before insert on card for each row execute function reject_backup_card()");
        try { assertThatThrownBy(()->backups.restore(user,id)).isInstanceOf(Exception.class); }
        finally { jdbc.execute("drop trigger reject_backup_card on card");jdbc.execute("drop function reject_backup_card()"); }
        assertThat(jdbc.queryForObject("select id from card where user_id=?",UUID.class,user)).isEqualTo(card);
        assertThat(jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user)).isEqualTo(epoch);
        assertThat(jdbc.queryForObject("select notes from personal_annotation where user_id=?",String.class,user)).isEqualTo("原笔记");
        assertThat(backups.job(user,id,true).path("status").asText()).isEqualTo("RESTORING");
        backups.restore(user,id);assertThat(backups.job(user,id,true).path("status").asText()).isEqualTo("READY");
    }
    @Test void changeAfterConfirmationAlsoBlocksReplacementAndInterruptedImportsRequireExplicitRetry() throws Exception {
        UUID export=export(),id=preflight(user,export);confirm(user,id);jdbc.update("update app_user set revision=revision+1 where id=?",user);
        assertThatThrownBy(()->backups.restore(user,id)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.RESTORE_STALE));
        assertThat(jdbc.queryForObject("select id from card where user_id=?",UUID.class,user)).isEqualTo(card);
        byte[] changed=changed(export,d->((ObjectNode)d.path("import_job").get(0)).put("status","PARSING"));
        UUID other=upload(user,changed);backups.validate(user,other);confirm(user,other);backups.restore(user,other);
        assertThat(jdbc.queryForObject("select status from import_job where user_id=?",String.class,user)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_code from import_job where user_id=?",String.class,user)).isEqualTo("RESTORED_INTERRUPTED");
    }
    @Test void jobsRequireAuthenticationCsrfAndExplicitConfirmationAndUploadDeduplicates() throws Exception {
        UUID id=preflight(user,export());var principal=new AccountPrincipal(user,"test","unused");
        mvc.perform(get("/api/backups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/backups").with(user(principal)).contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isForbidden());
        var p=backups.job(user,id,true).path("validation_result");
        mvc.perform(post("/api/restores/"+id+"/confirm").with(user(principal)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("dataEpoch",epoch,"expectedRevision",p.path("expectedRevision").asLong(),"confirmed",false))))
            .andExpect(status().isBadRequest());
        UUID operation=UUID.randomUUID();var file=new MockMultipartFile("file","backup.zip","application/zip",new byte[]{1,2,3});
        assertThat(backups.upload(user,operation,file)).isEqualTo(backups.upload(user,operation,file));
        assertThatThrownBy(()->backups.upload(user,operation,new MockMultipartFile("file",new byte[]{4}))).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.OPERATION_ID_REUSED));
    }
    @Test void archivedResourcesAreIndependentAndCompletedResultsSurviveArchiveExpiry() throws Exception {
        UUID export=export(),id=preflight(user,export);confirm(user,id);
        var row=jdbc.queryForMap("select storage_key,byte_size,sha256 from source_document where user_id=?",user);
        Path original=storage.read((UUID)row.get("storage_key")).getFile().toPath();byte[] saved=Files.readAllBytes(original);
        Files.delete(original);
        // Export references immutable files, but recovery uses its own archived copies.
        backups.restore(user,id);
        assertThat(jdbc.queryForObject("select notes from personal_annotation where user_id=?",String.class,user)).isEqualTo("原笔记");
        UUID restored=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,user);
        assertThat(storage.read(restored).getContentAsByteArray()).isEqualTo(saved);
        jdbc.update("update restore_job set updated_at=current_timestamp-interval '8 days' where id=?",id);
        jdbc.update("update backup_job set updated_at=current_timestamp-interval '8 days' where id=?",export);
        app.getBean(BackupWorker.class).cleanup();
        assertThat(backups.job(user,id,true).path("status").asText()).isEqualTo("READY");
        assertThat(backups.job(user,export,false).path("error_code").asText()).isEqualTo("BACKUP_EXPIRED");
        assertThat(storage.read(restored).getContentAsByteArray()).isEqualTo(saved);
    }
    @Test void missingStagingFileFailsBeforeAnyReplacement() throws Exception {
        UUID id=preflight(user,export());confirm(user,id);
        UUID archiveKey=jdbc.queryForObject("select storage_key from restore_job where id=?",UUID.class,id);storage.delete(archiveKey);
        assertThatThrownBy(()->backups.restore(user,id)).isInstanceOf(ApiFailure.class);
        assertThat(jdbc.queryForObject("select id from card where user_id=?",UUID.class,user)).isEqualTo(card);
        assertThat(jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user)).isEqualTo(epoch);
        assertThat(jdbc.queryForObject("select notes from personal_annotation where user_id=?",String.class,user)).isEqualTo("原笔记");
    }
}
