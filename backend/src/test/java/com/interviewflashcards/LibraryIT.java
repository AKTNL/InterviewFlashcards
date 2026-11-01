package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.*;
import com.interviewflashcards.admin.AdminService;
import com.interviewflashcards.backup.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.interviewflashcards.importing.*;
import com.interviewflashcards.library.*;
import com.interviewflashcards.sync.MutationService;
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
class LibraryIT {
    @TempDir static Path directory;
    final String schema="library_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;JdbcTemplate jdbc;ObjectMapper mapper;MockMvc mvc;
    LibraryService library;ImportService imports;ChapterReviewService review;FileStorage storage;UUID admin,user,other,id,owner,job,chapter;byte[] pdf;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username=library-preset","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);library=app.getBean(LibraryService.class);
        imports=app.getBean(ImportService.class);review=app.getBean(ChapterReviewService.class);storage=app.getBean(FileStorage.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account(String role){UUID u=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash,role) values(?,?,'test-only',?)",u,"u-"+u,role);return u;}
    UUID epoch(UUID u){return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,u);}
    long count(String table,UUID u){return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Long.class,u);}
    AccountPrincipal principal(UUID u){return new AccountPrincipal(u,"test","!");}
    void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,ErrorCode code){assertThatThrownBy(work).isInstanceOfSatisfying(ApiFailure.class,f->assertThat(f.code()).isEqualTo(code));}
    @BeforeEach void seed() throws Exception {
        admin=account("ADMIN");user=account("USER");other=account("USER");
        id=(UUID)library.create(admin,UUID.randomUUID(),"平台 Java","完整来源和章节").get("id");
        owner=jdbc.queryForObject("select owner_id from platform_library where id=?",UUID.class,id);
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()){document.addPage(new PDPage());document.addPage(new PDPage());document.save(out);pdf=out.toByteArray();}
        var result=library.manage(admin,id,"UPLOAD",u->app.getBean(SourceService.class).upload(u,new MutationService.Command(UUID.randomUUID(),epoch(u)),new MockMultipartFile("file","platform.pdf","application/pdf",pdf)));
        job=UUID.fromString(result.path("importJobId").asText());
        var ticket=imports.claim(job,owner);
        var span=Map.<String,Object>of("page",1,"bbox",List.of(0,0,100,100),"textSpans",List.of(Map.of("text","平台完整答案","bbox",List.of(0,0,100,100))),"images",List.of());
        var candidate=new PdfImportParser.Candidate("1","问题","第一章","第一章","Java",new PdfImportParser.Position(1,0),new PdfImportParser.Position(2,0),List.of(span),"完整答案","hash",List.of(Map.of("title","追问","page",1)),List.of(Map.of("kind","CHECK","reason","需人工核对","page",1)),Map.of());
        imports.complete(ticket,new PdfImportParser.Result(List.of(candidate),Map.of("candidateCount",1)));
        chapter=jdbc.queryForObject("select id from chapter where user_id=?",UUID.class,owner);
    }
    LibraryController.Transition command(String action)throws Exception {
        var p=library.preview(admin,id);
        return new LibraryController.Transition(UUID.randomUUID(),epoch(admin),((Number)p.get("version")).longValue(),((Number)p.get("revision")).longValue(),action,true,true);
    }
    void confirm()throws Exception {
        library.manage(admin,id,"REVIEW",u->review.write(u,chapter,new ChapterReviewController.Write(UUID.randomUUID(),epoch(u),(Long)review.view(u,chapter).get("version"),"CONFIRM",mapper.createObjectNode().put("allowFlagged",true))));
    }
    void publish()throws Exception {confirm();library.transition(admin,id,command("PUBLISH"));}
    LibraryController.Claim claimCommand(UUID u){return new LibraryController.Claim(UUID.randomUUID(),epoch(u),jdbc.queryForObject("select content_version from platform_library where id=?",Long.class,id),true);}
    @Test void platformNamespacesNeverBecomeHumanAccountsAndCreationIsIdempotent()throws Exception {
        UUID op=UUID.randomUUID();var first=library.create(admin,op,"独立平台内容","");assertThat(library.create(admin,op,"独立平台内容","").get("id")).isEqualTo(first.get("id"));
        error(()->library.create(admin,op,"更改内容",""),ErrorCode.OPERATION_ID_REUSED);
        assertThat(app.getBean(AdminService.class).users(admin,"@library:","ALL","ALL",0,20).get("total")).isEqualTo(0L);
        error(()->app.getBean(AdminService.class).detail(admin,owner),ErrorCode.NOT_FOUND);
        assertThat(jdbc.queryForMap("select space_kind,enabled,password_hash,role from app_user where id=?",owner)).containsEntry("space_kind","PLATFORM").containsEntry("enabled",false).containsEntry("password_hash","!").containsEntry("role","USER");
        assertThatThrownBy(()->jdbc.update("update app_user set enabled=true where id=?",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void unpublishedResourcesAndManagementAreNotAccessibleToUsersOrAnonymousClients()throws Exception {
        mvc.perform(get("/api/library")).andExpect(status().isOk());
        assertThat(library.catalog(0,20)).noneMatch(r->r.get("id").equals(id));
        for(String path:List.of("/api/admin/library","/api/admin/library/"+id+"/preview","/api/admin/library/"+id+"/jobs")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).with(user(principal(user)))).andExpect(status().isForbidden());
        }
        UUID source=jdbc.queryForObject("select source_id from import_job where id=?",UUID.class,job);
        mvc.perform(get("/api/admin/library/"+id+"/sources/"+source+"/content").with(user(principal(admin))).header("Range","bytes=0-4")).andExpect(status().isPartialContent()).andExpect(content().bytes("%PDF-".getBytes()));
        mvc.perform(get("/api/admin/library/"+id+"/sources/"+source+"/pages/1/image").with(user(principal(admin)))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/sources/"+source+"/content").with(user(principal(user)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/library/"+id+"/sources/"+source+"/content").with(user(principal(user)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library").with(user(principal(admin))).contentType("application/json").content(mapper.writeValueAsString(Map.of("operationId",UUID.randomUUID(),"name","拒绝 CSRF","description","")))).andExpect(status().isForbidden());
        error(()->library.create(user,UUID.randomUUID(),"无权",""),ErrorCode.FORBIDDEN);
        error(()->library.claim(user,id,new LibraryController.Claim(UUID.randomUUID(),epoch(user),1,true)),ErrorCode.LIBRARY_UNAVAILABLE);
    }
    @Test void publishingRequiresConfirmedContentCurrentPreviewAndExplicitRights()throws Exception {
        error(()->library.transition(admin,id,command("PUBLISH")),ErrorCode.CHECK_REQUIRED);
        error(()->library.manage(admin,id,"REVIEW",u->review.write(u,chapter,new ChapterReviewController.Write(UUID.randomUUID(),epoch(u),0L,"CONFIRM",mapper.createObjectNode().put("allowFlagged",false)))),ErrorCode.CHECK_REQUIRED);
        var stale=command("PUBLISH");confirm();error(()->library.transition(admin,id,stale),ErrorCode.DATA_PREVIEW_STALE);
        var c=command("PUBLISH");error(()->library.transition(admin,id,new LibraryController.Transition(c.operationId(),c.dataEpoch(),c.expectedVersion(),c.expectedRevision(),c.action(),true,false)),ErrorCode.INVALID_REQUEST);
        var result=library.transition(admin,id,c);assertThat(library.transition(admin,id,c)).isEqualTo(mapper.readTree(result.toString()));
        assertThat(library.catalog(0,20).toString()).contains("平台 Java","1–2").doesNotContain("storage_key","owner_id","password_hash");
        error(()->library.manage(admin,id,"REVIEW",u->null),ErrorCode.LIBRARY_NOT_EDITABLE);
    }
    @Test void receivedCopiesSurviveUnlistingDeletionCleanupLearningAndPersonalBackupRestore()throws Exception {
        publish();var c=claimCommand(user);var first=library.claim(user,id,c);assertThat(library.claim(user,id,c)).isEqualTo(mapper.readTree(first.toString()));
        assertThat(library.claim(user,id,claimCommand(user)).path("alreadyClaimed").asBoolean()).isTrue();assertThat(count("deck",user)).isOne();
        UUID key=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,user);
        UUID original=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,owner);assertThat(key).isNotEqualTo(original);
        for(String table:List.of("personal_annotation","review_state","review_event","learning_preference","learning_session"))assertThat(count(table,user)).isZero();
        library.transition(admin,id,command("UNLIST"));assertThat(library.catalog(0,20)).noneMatch(r->r.get("id").equals(id));error(()->library.claim(other,id,claimCommand(other)),ErrorCode.LIBRARY_UNAVAILABLE);
        library.manage(admin,id,"REVIEW",u->{
            UUID draft=jdbc.queryForObject("select id from draft_card where user_id=?",UUID.class,u);
            var value=mapper.valueToTree(imports.draft(u,draft));((com.fasterxml.jackson.databind.node.ObjectNode)value).put("question","平台更新后的新问题");
            var payload=mapper.createObjectNode().put("draftId",draft.toString()).put("reason","验证独立副本");payload.set("value",value);
            return review.write(u,chapter,new ChapterReviewController.Write(UUID.randomUUID(),epoch(u),(Long)review.view(u,chapter).get("version"),"EDIT",payload));
        });
        assertThat(jdbc.queryForObject("select question from card where user_id=?",String.class,user)).isEqualTo("问题");
        library.transition(admin,id,command("PUBLISH"));assertThat(jdbc.queryForObject("select content_version from platform_library where id=?",Long.class,id)).isEqualTo(2L);
        library.transition(admin,id,command("DELETE"));assertThat(storage.read(key).getContentAsByteArray()).isEqualTo(pdf);
        jdbc.update("update platform_library set deleted_at=current_timestamp-interval '8 days' where id=?",id);library.cleanup();
        assertThat(jdbc.queryForObject("select purged_at from platform_library where id=?",Object.class,id)).isNotNull();
        error(()->storage.read(original),ErrorCode.STORAGE_UNAVAILABLE);assertThat(storage.read(key).getContentAsByteArray()).isEqualTo(pdf);
        UUID card=jdbc.queryForObject("select id from card where user_id=?",UUID.class,user);
        var learning=app.getBean(com.interviewflashcards.learning.LearningService.class);
        assertThat(learning.start(user,new com.interviewflashcards.learning.LearningController.Start(UUID.randomUUID(),epoch(user),List.of(card))).path("session").isObject()).isTrue();
        var backup=app.getBean(BackupService.class);UUID export=UUID.fromString(backup.createExport(user,UUID.randomUUID()).path("id").asText());backup.export(user,export);
        UUID restore=UUID.fromString(backup.upload(user,UUID.randomUUID(),new MockMultipartFile("file","own.zip","application/zip",backup.download(user,export).getContentAsByteArray())).path("id").asText());
        backup.validate(user,restore);var impact=backup.job(user,restore,true).path("validation_result");backup.confirm(user,restore,UUID.fromString(impact.path("expectedEpoch").asText()),impact.path("expectedRevision").asLong(),true);backup.restore(user,restore);
        assertThat(count("card",user)).isOne();assertThat(jdbc.queryForObject("select status from platform_library where id=?",String.class,id)).isEqualTo("DELETED");
        UUID restored=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,user);assertThat(storage.read(restored).getContentAsByteArray()).isEqualTo(pdf);
        assertThat(jdbc.queryForObject("select copy_origin::text from deck where user_id=?",String.class,user)).contains(id.toString(),"contentVersion");
    }
    @Test void concurrentClaimAndDeleteYieldCompleteCopyOrNoCopy()throws Exception {
        publish();var deletion=command("DELETE");var claimCommand=claimCommand(user);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var claim=executor.submit(()->{gate.await();try{library.claim(user,id,claimCommand);return true;}catch(ApiFailure f){assertThat(f.code()).isEqualTo(ErrorCode.LIBRARY_UNAVAILABLE);return false;}});
            var delete=executor.submit(()->{gate.await();return library.transition(admin,id,deletion);});gate.countDown();
            boolean won=claim.get(15,TimeUnit.SECONDS);delete.get(15,TimeUnit.SECONDS);
            assertThat(count("deck",user)).isEqualTo(won?1:0);assertThat(count("source_document",user)).isEqualTo(won?1:0);
            if(won)assertThat(storage.read(jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,user)).getContentAsByteArray()).isEqualTo(pdf);
        }
    }
    @Test void concurrentDuplicateClaimsAcrossOperationsProduceOneDeck()throws Exception {
        publish();try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var first=executor.submit(()->{gate.await();return library.claim(user,id,claimCommand(user));});var second=executor.submit(()->{gate.await();return library.claim(user,id,claimCommand(user));});gate.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).path("deckId")).isEqualTo(second.get(15,TimeUnit.SECONDS).path("deckId"));assertThat(count("deck",user)).isOne();
        }
    }
    @Test void quotaCorruptionAndReceiptFailureRollbackAllContentAndFiles()throws Exception {
        publish();for(int i=0;i<20;i++)jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'quota.pdf',?,1,1,?)",UUID.randomUUID(),user,String.format("%064x",i),UUID.randomUUID());
        error(()->library.claim(user,id,claimCommand(user)),ErrorCode.QUOTA_EXCEEDED);assertThat(count("deck",user)).isZero();jdbc.update("delete from source_document where user_id=?",user);
        long files;try(var paths=Files.list(directory.resolve("files"))){files=paths.count();}
        jdbc.execute("create function reject_library_receipt() returns trigger language plpgsql as $$ begin if new.user_id='"+user+"'::uuid then raise exception 'test failure'; end if; return new; end $$");
        jdbc.execute("create trigger reject_library_receipt before insert on mutation_receipt for each row execute function reject_library_receipt()");
        try{assertThatThrownBy(()->library.claim(user,id,claimCommand(user))).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("drop trigger reject_library_receipt on mutation_receipt");jdbc.execute("drop function reject_library_receipt()");}
        assertThat(count("deck",user)).isZero();assertThat(library.claims(user)).isEmpty();try(var paths=Files.list(directory.resolve("files"))){assertThat(paths.count()).isEqualTo(files);}
        UUID sourceKey=jdbc.queryForObject("select storage_key from source_document where user_id=?",UUID.class,owner);Files.writeString(storage.read(sourceKey).getFile().toPath(),"corrupt");
        error(()->library.claim(user,id,claimCommand(user)),ErrorCode.STORAGE_UNAVAILABLE);assertThat(count("deck",user)).isZero();
    }
    @Test void deletingRunningImportFencesLateWorkerAndRetainsResourcesForSevenDays()throws Exception {
        jdbc.update("update import_job set status='QUEUED' where id=?",job);var ticket=imports.claim(job,owner);
        library.transition(admin,id,command("DELETE"));assertThat(imports.checkpoint(ticket,"PERSISTING",90,null)).isFalse();
        library.cleanup();assertThat(jdbc.queryForObject("select purged_at from platform_library where id=?",Object.class,id)).isNull();
        assertThat(jdbc.queryForObject("select status from import_job where id=?",String.class,job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from platform_library_audit where library_id=? and action='DELETE' and result='SUCCESS'",Long.class,id)).isOne();
    }
}
