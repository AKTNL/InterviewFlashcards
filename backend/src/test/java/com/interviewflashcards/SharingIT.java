package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.backup.*;
import com.interviewflashcards.cards.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.interviewflashcards.learning.*;
import com.interviewflashcards.sharing.*;
import java.io.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharingIT {
    @TempDir static Path directory;
    final String schema="sharing_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;
    JdbcTemplate jdbc;ObjectMapper mapper;ShareService shares;FileStorage storage;LearningService learning;MockMvc mvc;
    UUID a,b,c,deck,chapter,card,source;String recipient;byte[] pdf;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username=sharing-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);shares=app.getBean(ShareService.class);storage=app.getBean(FileStorage.class);learning=app.getBean(LearningService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var cn=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=cn.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account() {UUID u=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'private-credential')",u,"u-"+u);return u;}
    UUID epoch(UUID u) {return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,u);}
    long count(String table,UUID u) {return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Long.class,u);}
    UUID publish(byte[] bytes) throws Exception {UUID key=UUID.randomUUID();try(var staged=storage.stage(new ByteArrayInputStream(bytes))){storage.publish(staged,key);}return key;}
    @BeforeEach void seed() throws Exception {
        a=account();b=account();c=account();recipient=jdbc.queryForObject("select username from app_user where id=?",String.class,b);
        UUID module=UUID.randomUUID(),job=UUID.randomUUID(),draft=UUID.randomUUID();deck=UUID.randomUUID();chapter=UUID.randomUUID();card=UUID.randomUUID();source=UUID.randomUUID();
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.addPage(new PDPage());doc.addPage(new PDPage());doc.save(out);pdf=out.toByteArray();}
        UUID key=publish(pdf);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'complete.pdf',?,3,?,?)",source,a,BackupArchive.hash(storage.read(key).getFile().toPath()),pdf.length,key);
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'Java')",deck,a);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,a,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'已确认','已确认','IMPORTED')",chapter,a,module);
        jdbc.update("insert into import_job(id,user_id,source_id,deck_id,status,raw_evidence,report) values(?,?,?,?,'READY','{\"privateUnchecked\":true}','{\"privateReport\":true}')",job,a,source,deck);
        String content="[{\"type\":\"SOURCE_TEXT\",\"page\":1,\"bbox\":[0,0,100,100],\"textSpans\":[{\"text\":\"完整答案\"}],\"images\":[]}]";
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,content_blocks,original_content,check_flags) values(?,?,?,?,'1','问题',?::jsonb,?::jsonb,'[{\"kind\":\"CHECK\",\"reason\":\"需核对\"}]')",draft,a,chapter,job,content,content);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question,original_content,display_content,needs_check,technical_correction,applicable_version) values(?,?,?,?,'问题',?::jsonb,?::jsonb,true,'技术纠错','Java21')",card,a,chapter,draft,content,content);
        jdbc.update("insert into personal_annotation(id,user_id,card_id,notes,hint,memory_points,favorite) values(?,?,?,'PRIVATE-NOTE','PRIVATE-HINT','PRIVATE-MEMORY',true)",UUID.randomUUID(),a,card);
        UUID pending=UUID.randomUUID();jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'未确认','未确认')",pending,a,module);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'2','PRIVATE-DRAFT')",UUID.randomUUID(),a,pending,job);
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var imageOut=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",imageOut);byte[] png=imageOut.toByteArray();UUID asset=UUID.randomUUID(),imageKey=publish(png);
        jdbc.update("insert into asset(id,user_id,source_id,storage_key,media_type,byte_size,sha256) values(?,?,?,?,'image/png',?,?)",asset,a,source,imageKey,png.length,BackupArchive.hash(storage.read(imageKey).getFile().toPath()));
        jdbc.update("insert into source_span(id,user_id,source_id,card_id,asset_id,physical_page,region) values(?,?,?,?,?,1,'[0,0,100,100]')",UUID.randomUUID(),a,source,card,asset);
        jdbc.update("insert into follow_up(id,user_id,card_id,content) values(?,?,?,'{\"title\":\"追问\",\"page\":1}')",UUID.randomUUID(),a,card);
        UUID tag=UUID.randomUUID();jdbc.update("insert into tag(id,user_id,name) values(?,?,'重点')",tag,a);jdbc.update("insert into card_tag values(?,?,?)",a,card,tag);
        var session=learning.start(a,new LearningController.Start(UUID.randomUUID(),epoch(a),List.of(card))).path("session");rate(a,session);
    }
    JsonNode rate(UUID u,JsonNode s) throws Exception {
        var p=mapper.createObjectNode().put("itemId",s.at("/current/id").asText()).put("expectedReviewVersion",s.at("/current/expectedVersion").asLong()).put("answerRevealed",true).put("confirmEarly",true).put("rating","REMEMBERED");
        return learning.write(u,UUID.fromString(s.path("id").asText()),new LearningController.Write(UUID.randomUUID(),epoch(u),s.path("version").asLong(),"RATE",p));
    }
    ShareController.Create createCommand() {var p=shares.preview(a,deck);return new ShareController.Create(UUID.randomUUID(),epoch(a),deck,p.path("revision").asLong(),recipient,24,true);}
    UUID invite() throws Exception {return UUID.fromString(shares.create(a,createCommand()).at("/share/id").asText());}
    ShareController.Claim claimCommand() {return new ShareController.Claim(UUID.randomUUID(),epoch(b),true);}
    void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,ErrorCode code) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    @Test void contentSnapshotIsPrivateImmutableAndCopiesFullPdfIntoIndependentOwnership() throws Exception {
        var cmd=createCommand();var created=shares.create(a,cmd);assertThat(shares.create(a,cmd)).isEqualTo(mapper.readTree(created.toString()));
        UUID id=UUID.fromString(created.at("/share/id").asText());
        assertThat(created.at("/share/manifest/counts/card").asInt()).isOne();assertThat(created.at("/share/manifest/counts/chapter").asInt()).isOne();
        assertThat(created.at("/share/manifest/sources/0/includedPages").asText()).isEqualTo("1–3");
        String snapshot=jdbc.queryForObject("select snapshot::text from deck_share where id=?",String.class,id);
        assertThat(snapshot).doesNotContain("PRIVATE-","privateUnchecked","privateReport","password","personal_annotation","review_event");
        assertThat(shares.invitation(b,id).toString()).doesNotContain("storage_key","recipient_id","owner_epoch");
        jdbc.update("update card set question='原用户修改' where id=?",card);
        var claim=claimCommand();var received=shares.claim(b,id,claim);assertThat(shares.claim(b,id,claim)).isEqualTo(mapper.readTree(received.toString()));
        assertThat(count("deck",b)).isOne();assertThat(count("card",b)).isOne();
        for(String t:List.of("personal_annotation","review_state","review_event","learning_preference","learning_session","sync_conflict"))assertThat(count(t,b)).as(t).isZero();
        UUID newCard=jdbc.queryForObject("select id from card where user_id=?",UUID.class,b);assertThat(newCard).isNotEqualTo(card);
        var detail=app.getBean(CardService.class).detail(b,newCard);assertThat(detail.get("question")).isEqualTo("问题");assertThat(detail.get("needsCheck")).isEqualTo(true);assertThat(detail.get("technicalCorrection")).isEqualTo("技术纠错");
        assertThat(detail.get("tags")).asList().containsExactly("重点");assertThat(detail.get("followups")).asList().hasSize(1);
        assertThat(count("source_span",b)).isEqualTo(2);assertThat(count("follow_up",b)).isEqualTo(2);
        UUID receivedKey=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,b);
        assertThat(storage.read(receivedKey).getContentAsByteArray()).isEqualTo(pdf);
        assertThat(receivedKey).isNotEqualTo(jdbc.queryForObject("select storage_key from source_document where id=?",UUID.class,source));
        rate(b,learning.start(b,new LearningController.Start(UUID.randomUUID(),epoch(b),List.of(newCard))).path("session"));
        assertThat(count("review_state",a)).isOne();assertThat(count("review_event",a)).isOne();
        // Exercise the real content editor against the copied draft/source graph after learning.
        var imports=app.getBean(com.interviewflashcards.importing.ImportService.class);
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(imports.draft(b,(UUID)detail.get("draftId")));
        value.put("question","B 独立修改");
        var edit=mapper.createObjectNode().put("draftId",detail.get("draftId").toString()).put("reason","接收者独立整理");edit.set("value",value);
        app.getBean(com.interviewflashcards.importing.ChapterReviewService.class).write(b,(UUID)detail.get("chapterId"),
            new com.interviewflashcards.importing.ChapterReviewController.Write(UUID.randomUUID(),epoch(b),((Number)detail.get("chapterVersion")).longValue(),"EDIT",edit));
        assertThat(app.getBean(CardService.class).detail(b,newCard).get("question")).isEqualTo("B 独立修改");
        assertThat(app.getBean(CardService.class).detail(a,card).get("question")).isEqualTo("原用户修改");assertThat(count("review_state",b)).isOne();
        shares.revoke(a,id);assertThat(storage.read(receivedKey).getContentAsByteArray()).isEqualTo(pdf);
        var data=app.getBean(DeckDataService.class);data.confirm(a,deck,new DeckDataController.Confirm(UUID.randomUUID(),epoch(a),(Long)data.preview(a,deck).get("revision"),"DELETE",true));
        assertThat(app.getBean(CardService.class).detail(b,newCard).get("question")).isEqualTo("B 独立修改");
    }
    @Test void duplicatePdfAndRecipientTagAreSafeAndProvenanceSurvivesBackupRestore() throws Exception {
        // Same bytes can arrive through a normal upload and two separately authorized content snapshots.
        UUID uploaded=UUID.randomUUID(),key=publish(pdf);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'existing.pdf',?,3,?,?)",uploaded,b,BackupArchive.hash(storage.read(key).getFile().toPath()),pdf.length,key);
        jdbc.update("insert into tag(id,user_id,name) values(?,?,'重点')",UUID.randomUUID(),b);
        shares.claim(b,invite(),claimCommand());shares.claim(b,invite(),claimCommand());assertThat(count("source_document",b)).isEqualTo(3);assertThat(count("tag",b)).isOne();
        assertThat(app.getBean(SourceDocumentRepository.class).findByUserIdAndSha256(b,BackupArchive.hash(storage.read(key).getFile().toPath())).orElseThrow().getId()).isEqualTo(uploaded);
        var origins=jdbc.queryForList("select copy_origin::text from deck where user_id=? order by copy_origin::text",String.class,b);
        var backup=app.getBean(BackupService.class);UUID export=UUID.fromString(backup.createExport(b,UUID.randomUUID()).path("id").asText());backup.export(b,export);
        UUID restore=UUID.fromString(backup.upload(c,UUID.randomUUID(),new MockMultipartFile("file","copy.zip","application/zip",backup.download(b,export).getContentAsByteArray())).path("id").asText());
        backup.validate(c,restore);var p=backup.job(c,restore,true).path("validation_result");backup.confirm(c,restore,UUID.fromString(p.path("expectedEpoch").asText()),p.path("expectedRevision").asLong(),true);backup.restore(c,restore);
        assertThat(jdbc.queryForList("select copy_origin::text from deck where user_id=? order by copy_origin::text",String.class,c)).isEqualTo(origins);
        assertThat(count("card",c)).isEqualTo(2);assertThat(count("deck_share",c)).isZero();
    }
    @Test void authorizationExplicitConsentCsrfAndNoAnonymousFiles() throws Exception {
        UUID id=invite();var principal=new AccountPrincipal(c,"c","unused");
        mvc.perform(get("/api/shares/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shares/"+id).with(user(principal))).andExpect(status().isNotFound());
        mvc.perform(post("/api/shares/"+id+"/claim").with(user(principal)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(new ShareController.Claim(UUID.randomUUID(),epoch(c),true)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources/"+source+"/content").with(user(principal))).andExpect(status().isNotFound());
        var bp=new AccountPrincipal(b,"b","unused");
        mvc.perform(post("/api/shares/"+id+"/claim").with(user(bp)).contentType("application/json").content(mapper.writeValueAsString(claimCommand()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/shares/"+id+"/claim").with(user(bp)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(new ShareController.Claim(UUID.randomUUID(),epoch(b),false)))).andExpect(status().isBadRequest());
        error(()->shares.revoke(c,id),ErrorCode.NOT_FOUND);error(()->shares.claim(a,id,new ShareController.Claim(UUID.randomUUID(),epoch(a),true)),ErrorCode.NOT_FOUND);
        assertThat(count("deck",b)).isZero();
    }
    @Test void expiredRevokedStaleAndWrongEpochAreRejected() throws Exception {
        var cmd=createCommand();jdbc.update("update app_user set revision=revision+1 where id=?",a);error(()->shares.create(a,cmd),ErrorCode.DATA_PREVIEW_STALE);
        UUID expired=invite();jdbc.update("update deck_share set expires_at=current_timestamp-interval '1 second' where id=?",expired);error(()->shares.claim(b,expired,claimCommand()),ErrorCode.SHARE_UNAVAILABLE);
        UUID revoked=invite();shares.revoke(a,revoked);error(()->shares.claim(b,revoked,claimCommand()),ErrorCode.SHARE_UNAVAILABLE);
        UUID id=invite();error(()->shares.claim(b,id,new ShareController.Claim(UUID.randomUUID(),UUID.randomUUID(),true)),ErrorCode.EPOCH_EXPIRED);
        shares.claim(b,id,claimCommand());error(()->shares.claim(b,id,claimCommand()),ErrorCode.SHARE_UNAVAILABLE);assertThat(count("deck",b)).isOne();
    }
    @Test void lateReceiptFailureRollsBackAllRowsAndPublishedFilesThenOriginalOperationCanRetry() throws Exception {
        UUID id=invite();var cmd=claimCommand();long files;try(var paths=Files.list(directory.resolve("files"))){files=paths.count();}
        jdbc.execute("create function reject_share_receipt() returns trigger language plpgsql as $$ begin if new.user_id='"+b+"'::uuid then raise exception 'test failure'; end if; return new; end $$");
        jdbc.execute("create trigger reject_share_receipt before insert on mutation_receipt for each row execute function reject_share_receipt()");
        try {assertThatThrownBy(()->shares.claim(b,id,cmd)).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("drop trigger reject_share_receipt on mutation_receipt");jdbc.execute("drop function reject_share_receipt()");}
        assertThat(count("deck",b)).isZero();assertThat(count("source_document",b)).isZero();assertThat(count("mutation_receipt",b)).isZero();assertThat(shares.invitation(b,id).path("claimCount").asInt()).isZero();
        try(var paths=Files.list(directory.resolve("files"))){assertThat(paths.count()).isEqualTo(files);}
        shares.claim(b,id,cmd);assertThat(count("card",b)).isOne();
    }
    @Test void quotaAndCorruptFileNeverLeavePartialDeck() throws Exception {
        UUID id=invite();for(int i=0;i<20;i++)jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'quota.pdf',?,1,1,?)",UUID.randomUUID(),b,String.format("%064x",i),UUID.randomUUID());
        error(()->shares.claim(b,id,claimCommand()),ErrorCode.QUOTA_EXCEEDED);assertThat(count("deck",b)).isZero();
        jdbc.update("delete from source_document where user_id=?",b);
        UUID key=jdbc.queryForObject("select storage_key from share_resource where share_id=? order by byte_size desc limit 1",UUID.class,id);Files.writeString(storage.read(key).getFile().toPath(),"broken");
        error(()->shares.claim(b,id,claimCommand()),ErrorCode.STORAGE_UNAVAILABLE);assertThat(count("source_document",b)).isZero();assertThat(count("deck",b)).isZero();
    }
    @Test void concurrentClaimsAndRevokeHaveOneSerializedOutcome() throws Exception {
        UUID id=invite();var cmd=claimCommand();
        try(var executor=Executors.newFixedThreadPool(3)) {
            var gate=new CountDownLatch(1);var first=executor.submit(()->{gate.await();return shares.claim(b,id,cmd);});var second=executor.submit(()->{gate.await();return shares.claim(b,id,cmd);});gate.countDown();
            assertThat(mapper.readTree(first.get(15,TimeUnit.SECONDS).toString())).isEqualTo(mapper.readTree(second.get(15,TimeUnit.SECONDS).toString()));assertThat(count("deck",b)).isOne();
            UUID race=invite();var barrier=new CountDownLatch(1);
            var claim=executor.submit(()->{barrier.await();try {shares.claim(b,race,claimCommand());return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.SHARE_UNAVAILABLE);return false;}});
            var revoke=executor.submit(()->{barrier.await();return shares.revoke(a,race);});barrier.countDown();boolean won=claim.get(15,TimeUnit.SECONDS);revoke.get(15,TimeUnit.SECONDS);
            assertThat(count("deck",b)).isEqualTo(won?2:1);assertThat(shares.invitation(b,race).path("revoked").asBoolean()).isTrue();
        }
    }
    @Test void sourceDeletionBeforeClaimUsesTheSnapshotAndRestoreRevokesUnclaimedInvitations() throws Exception {
        UUID preserved=invite();var data=app.getBean(DeckDataService.class);
        data.confirm(a,deck,new DeckDataController.Confirm(UUID.randomUUID(),epoch(a),(Long)data.preview(a,deck).get("revision"),"DELETE",true));
        UUID originalKey=jdbc.queryForObject("select storage_key from source_document where id=?",UUID.class,source);storage.delete(originalKey);
        shares.claim(b,preserved,claimCommand());assertThat(count("card",b)).isOne();
        // Restoring B's own archive revokes B -> C invitations but preserves the received content.
        UUID copied=jdbc.queryForObject("select id from deck where user_id=?",UUID.class,b);var p=shares.preview(b,copied);
        UUID outgoing=UUID.fromString(shares.create(b,new ShareController.Create(UUID.randomUUID(),epoch(b),copied,p.path("revision").asLong(),jdbc.queryForObject("select username from app_user where id=?",String.class,c),24,true)).at("/share/id").asText());
        var backup=app.getBean(BackupService.class);UUID export=UUID.fromString(backup.createExport(b,UUID.randomUUID()).path("id").asText());backup.export(b,export);
        UUID restore=UUID.fromString(backup.upload(b,UUID.randomUUID(),new MockMultipartFile("file","own.zip","application/zip",backup.download(b,export).getContentAsByteArray())).path("id").asText());
        backup.validate(b,restore);var impact=backup.job(b,restore,true).path("validation_result");backup.confirm(b,restore,UUID.fromString(impact.path("expectedEpoch").asText()),impact.path("expectedRevision").asLong(),true);backup.restore(b,restore);
        assertThat(shares.invitation(c,outgoing).path("revoked").asBoolean()).isTrue();assertThat(count("share_resource",b)).isZero();
        error(()->shares.claim(c,outgoing,new ShareController.Claim(UUID.randomUUID(),epoch(c),true)),ErrorCode.SHARE_UNAVAILABLE);
        assertThat(count("card",b)).isOne();
    }
    @Test void reciprocalInvitationsDoNotBlockForeignKeyChecks() throws Exception {
        shares.claim(b,invite(),claimCommand());UUID copied=jdbc.queryForObject("select id from deck where user_id=?",UUID.class,b);
        var pa=shares.preview(a,deck);var pb=shares.preview(b,copied);
        var ca=new ShareController.Create(UUID.randomUUID(),epoch(a),deck,pa.path("revision").asLong(),recipient,24,true);
        var cb=new ShareController.Create(UUID.randomUUID(),epoch(b),copied,pb.path("revision").asLong(),jdbc.queryForObject("select username from app_user where id=?",String.class,a),24,true);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var first=executor.submit(()->{gate.await();return shares.create(a,ca);});var second=executor.submit(()->{gate.await();return shares.create(b,cb);});gate.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).at("/share/id").asText()).isNotBlank();assertThat(second.get(15,TimeUnit.SECONDS).at("/share/id").asText()).isNotBlank();
        }
    }
}
