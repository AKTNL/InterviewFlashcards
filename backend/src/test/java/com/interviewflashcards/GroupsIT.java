package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.backup.BackupService;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.groups.*;
import com.interviewflashcards.learning.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
class GroupsIT {
    @TempDir static Path directory;
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-11-01T04:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @TestConfiguration static class TimeOverride { @Bean @Primary TestClock groupTestClock(){return new TestClock();} }
    final String schema="groups_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;JdbcTemplate jdbc;ObjectMapper mapper;GroupService groups;LearningService learning;MockMvc mvc;TestClock clock;
    UUID a,b,c,g;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class,TimeOverride.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username=groups-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);groups=app.getBean(GroupService.class);learning=app.getBean(LearningService.class);clock=app.getBean(TestClock.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var cn=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=cn.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account() {UUID id=UUID.randomUUID();jdbc.update("insert into app_user(id,username,nickname,password_hash) values(?,?,'朋友昵称','private-credential')",id,"u-"+id);return id;}
    UUID epoch(UUID id) {return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,id);}
    @BeforeEach void seed() {clock.value=Instant.parse("2026-11-01T04:00:00Z");a=account();b=account();c=account();g=UUID.randomUUID();groups.create(a,new GroupController.Create(g,"Java 小组",true));}
    UUID invite(UUID user) {UUID id=UUID.randomUUID();groups.invite(a,g,new GroupController.Invite(id,jdbc.queryForObject("select username from app_user where id=?",String.class,user),24));return id;}
    void join(UUID user) {groups.join(user,g,invite(user),new GroupController.Join(true));}
    long version(UUID user) {return ((Number)groups.detail(user,g).get("version")).longValue();}
    void consent(UUID user,boolean sharing) {groups.consent(user,g,new GroupController.Consent(version(user),sharing));}
    JsonNode detail(UUID user) {return mapper.valueToTree(groups.detail(user,g));}
    JsonNode self(UUID user) {for(var m:detail(user).path("members"))if(m.path("self").asBoolean())return m;throw new AssertionError();}
    interface Work {void run() throws Exception;}
    void error(Work action,ErrorCode code) {assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    @Test void explicitInvitationAndPerGroupConsentExposeOnlyWhitelistedSummary() {
        UUID id=invite(b);assertThat(detail(a).path("members")).hasSize(1);
        error(()->groups.detail(b,g),ErrorCode.NOT_FOUND);error(()->groups.join(c,g,id,new GroupController.Join(true)),ErrorCode.NOT_FOUND);
        error(()->groups.join(b,g,id,new GroupController.Join(false)),ErrorCode.INVALID_REQUEST);
        groups.join(b,g,id,new GroupController.Join(true));assertThat(detail(a).path("members")).hasSize(2);
        assertThat(self(b).has("summary")).isFalse();consent(b,true);
        var summary=self(b).path("summary");assertThat(summary.fieldNames()).toIterable().containsExactlyInAnyOrder("learningDate","learningZone","studiedToday","todayNew","todayReview","weekLearningDays");
        assertThat(summary.path("learningZone").asText()).isEqualTo("Asia/Shanghai");assertThat(summary.path("studiedToday").asBoolean()).isFalse();
        assertThat(detail(a).path("members").toString()).doesNotContain("private-credential","rating","notes","cardId","history",b.toString(),"u-"+b);
        UUID other=UUID.randomUUID();groups.create(b,new GroupController.Create(other,"另一个组",true));assertThat(groups.detail(b,other)).containsEntry("sharing",false);
        assertThat(groups.list(c).get("groups")).asList().isEmpty();
    }
    @Test void withdrawalLeaveAndDissolutionImmediatelyBlockAccessAndStaleWrites() {
        UUID id=invite(b);groups.join(b,g,id,new GroupController.Join(true));consent(b,true);long old=version(b);consent(b,false);
        error(()->groups.consent(b,g,new GroupController.Consent(old,true)),ErrorCode.VERSION_CONFLICT);
        assertThat(self(b).has("summary")).isFalse();groups.join(b,g,id,new GroupController.Join(true));assertThat(groups.detail(b,g)).containsEntry("sharing",false);
        groups.leave(b,g);groups.leave(b,g);error(()->groups.detail(b,g),ErrorCode.NOT_FOUND);error(()->groups.join(b,g,id,new GroupController.Join(true)),ErrorCode.GROUP_INVITE_UNAVAILABLE);
        assertThat(detail(a).path("members")).hasSize(1);join(b);assertThat(groups.detail(b,g)).containsEntry("sharing",false);
        error(()->groups.consent(b,g,new GroupController.Consent(old,true)),ErrorCode.VERSION_CONFLICT);
        groups.leave(a,g);error(()->groups.detail(a,g),ErrorCode.NOT_FOUND);error(()->groups.detail(b,g),ErrorCode.NOT_FOUND);
        assertThat(groups.list(b).get("groups")).asList().isEmpty();
    }
    @Test void expiredRevokedAndNonOwnerInvitationsAreRejected() {
        UUID expired=invite(b);jdbc.update("update study_group_invite set expires_at=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),expired);
        error(()->groups.join(b,g,expired,new GroupController.Join(true)),ErrorCode.GROUP_INVITE_UNAVAILABLE);
        UUID revoked=invite(b);groups.revoke(a,g,revoked);error(()->groups.join(b,g,revoked,new GroupController.Join(true)),ErrorCode.GROUP_INVITE_UNAVAILABLE);
        join(b);error(()->groups.invite(b,g,new GroupController.Invite(UUID.randomUUID(),"other",24)),ErrorCode.NOT_FOUND);
        error(()->groups.revoke(b,g,revoked),ErrorCode.NOT_FOUND);error(()->groups.leave(c,g),ErrorCode.NOT_FOUND);
    }
    @Test void creationInvitationAndAcceptanceRetriesDoNotDuplicateOrReenableConsent() {
        var create=new GroupController.Create(g,"Java 小组",true);groups.create(a,create);
        UUID id=UUID.randomUUID();var cmd=new GroupController.Invite(id,jdbc.queryForObject("select username from app_user where id=?",String.class,b),24);
        groups.invite(a,g,cmd);var expires=jdbc.queryForObject("select expires_at from study_group_invite where id=?",java.sql.Timestamp.class,id);groups.invite(a,g,cmd);
        assertThat(jdbc.queryForObject("select expires_at from study_group_invite where id=?",java.sql.Timestamp.class,id)).isEqualTo(expires);
        groups.join(b,g,id,new GroupController.Join(true));consent(b,true);groups.join(b,g,id,new GroupController.Join(true));assertThat(groups.detail(b,g)).containsEntry("sharing",true);
        assertThat(jdbc.queryForObject("select count(*) from study_group_member where group_id=?",Integer.class,g)).isEqualTo(2);
        error(()->groups.create(c,create),ErrorCode.NOT_FOUND);
    }
    @Test void httpRequiresAuthenticationCsrfConfirmationAndCurrentMembership() throws Exception {
        mvc.perform(get("/api/groups/"+g)).andExpect(status().isUnauthorized());
        var bp=new AccountPrincipal(b,"b","unused");
        mvc.perform(get("/api/groups/"+g).with(user(bp))).andExpect(status().isNotFound());
        UUID id=invite(b);
        mvc.perform(post("/api/groups/"+g+"/join/"+id).with(user(bp)).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/groups/"+g+"/join/"+id).with(user(bp)).with(csrf()).contentType("application/json").content("{\"confirmed\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/groups/"+g+"/join/"+id).with(user(bp)).with(csrf()).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/groups/"+g).with(user(bp))).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/groups/"+g+"/consent").with(user(bp)).with(csrf()).contentType("application/json").content("{\"expectedVersion\":-1,\"sharing\":true}")).andExpect(status().isBadRequest());
        groups.leave(b,g);mvc.perform(get("/api/groups/"+g).with(user(bp))).andExpect(status().isNotFound());
    }
    UUID card(UUID user) {
        UUID source=UUID.randomUUID(),deck=UUID.randomUUID(),module=UUID.randomUUID(),chapter=UUID.randomUUID(),job=UUID.randomUUID(),draft=UUID.randomUUID(),card=UUID.randomUUID();
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'test.pdf',?,1,10,?)",source,user,UUID.randomUUID().toString().replace("-","").repeat(2),UUID.randomUUID());
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",deck,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,user,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'Java','Java','IMPORTED')",chapter,user,module);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id) values(?,?,?,'READY',?)",job,user,source,deck);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'q','私有题目')",draft,user,chapter,job);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'私有题目')",card,user,chapter,draft);return card;
    }
    JsonNode start(UUID card) throws Exception {return learning.start(b,new LearningController.Start(UUID.randomUUID(),epoch(b),List.of(card))).path("session");}
    LearningController.Write rating(JsonNode s) {
        var p=mapper.createObjectNode().put("itemId",s.at("/current/id").asText()).put("expectedReviewVersion",s.at("/current/expectedVersion").asLong()).put("answerRevealed",true).put("confirmEarly",true).put("rating","REMEMBERED");
        return new LearningController.Write(UUID.randomUUID(),epoch(b),s.path("version").asLong(),"RATE",p);
    }
    @Test void summaryMatchesPersonalStatisticsDeduplicatesRetriesReplaysUndoAndLearningDays() throws Exception {
        join(b);consent(b,true);UUID card=card(b);
        clock.value=Instant.parse("2026-10-25T16:05:00Z"); // Oct 26 in the learning zone; inside Nov 1's seven-day window.
        var first=start(card);learning.write(b,UUID.fromString(first.path("id").asText()),rating(first));
        clock.value=Instant.parse("2026-10-31T16:05:00Z"); // Nov 1 although UTC is still Oct 31.
        var s=start(card);var command=rating(s);UUID sid=UUID.fromString(s.path("id").asText());
        learning.write(b,sid,command);learning.write(b,sid,command);
        var replay=start(card);learning.write(b,UUID.fromString(replay.path("id").asText()),rating(replay));
        var summary=self(b).path("summary");var personal=learning.summary(b);
        assertThat(summary.path("learningDate").asText()).isEqualTo("2026-11-01");assertThat(summary.path("todayReview").asInt()).isOne();assertThat(summary.path("todayNew").asInt()).isZero();assertThat(summary.path("weekLearningDays").asInt()).isEqualTo(2);
        assertThat(summary.path("todayReview").asLong()).isEqualTo(((Number)personal.get("todayReview")).longValue());
        UUID fresh=card(b);var n=start(fresh);var scored=learning.write(b,UUID.fromString(n.path("id").asText()),rating(n)).path("session");
        assertThat(self(b).at("/summary/todayNew").asInt()).isOne();
        learning.write(b,UUID.fromString(n.path("id").asText()),new LearningController.Write(UUID.randomUUID(),epoch(b),scored.path("version").asLong(),"UNDO",mapper.createObjectNode().put("eventId",scored.at("/events/0/id").asText())));
        assertThat(self(b).at("/summary/todayNew").asInt()).isZero();
        clock.value=Instant.parse("2026-11-02T16:05:00Z");assertThat(self(b).at("/summary/weekLearningDays").asInt()).isOne();assertThat(self(b).at("/summary/studiedToday").asBoolean()).isFalse();
    }
    byte[] export(UUID user) throws Exception {
        var backups=app.getBean(BackupService.class);UUID id=UUID.fromString(backups.createExport(user,UUID.randomUUID()).path("id").asText());backups.export(user,id);return backups.download(user,id).getContentAsByteArray();
    }
    void restore(UUID user,byte[] bytes) throws Exception {
        var backups=app.getBean(BackupService.class);UUID id=UUID.fromString(backups.upload(user,UUID.randomUUID(),new MockMultipartFile("file","group.zip","application/zip",bytes)).path("id").asText());
        backups.validate(user,id);var p=backups.job(user,id,true).path("validation_result");backups.confirm(user,id,UUID.fromString(p.path("expectedEpoch").asText()),p.path("expectedRevision").asLong(),true);backups.restore(user,id);
    }
    @Test void actualPersonalBackupRestoreNeverRestoresMembershipOrRevokedConsent() throws Exception {
        join(b);consent(b,true);byte[] backup=export(b);consent(b,false);restore(b,backup);
        assertThat(groups.detail(b,g)).containsEntry("sharing",false);groups.leave(b,g);restore(b,backup);error(()->groups.detail(b,g),ErrorCode.NOT_FOUND);
        restore(c,backup);assertThat(groups.list(c).get("groups")).asList().isEmpty();assertThat(detail(a).path("members")).hasSize(1);
    }
    @Test void concurrentAcceptAndRevokeHaveOneSerializedOutcomeAndStaleConsentCannotWin() throws Exception {
        UUID id=invite(b);try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var joined=pool.submit(()->{gate.await();try {groups.join(b,g,id,new GroupController.Join(true));return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.GROUP_INVITE_UNAVAILABLE);return false;}});
            var revoked=pool.submit(()->{gate.await();groups.revoke(a,g,id);return true;});gate.countDown();boolean accepted=joined.get(10,TimeUnit.SECONDS);revoked.get(10,TimeUnit.SECONDS);
            if(accepted)assertThat(groups.detail(b,g)).containsEntry("sharing",false);else error(()->groups.detail(b,g),ErrorCode.NOT_FOUND);
            if(!accepted)join(b);
            long v=version(b);var consentGate=new CountDownLatch(1);
            Callable<Boolean> write=()->{consentGate.await();try{groups.consent(b,g,new GroupController.Consent(v,true));return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);return false;}};
            var one=pool.submit(write);var two=pool.submit(write);consentGate.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            consent(b,false);error(()->groups.consent(b,g,new GroupController.Consent(v,true)),ErrorCode.VERSION_CONFLICT);
        }
    }
    @Test void ownerLimitAndFinalMemberSlotAreEnforcedOnServer() throws Exception {
        for(int i=0;i<4;i++)groups.create(a,new GroupController.Create(UUID.randomUUID(),"额外小组",true));
        error(()->groups.create(a,new GroupController.Create(UUID.randomUUID(),"超限",true)),ErrorCode.GROUP_LIMITED);
        for(int i=0;i<48;i++)jdbc.update("insert into study_group_member(group_id,user_id) values(?,?)",g,account());
        UUID ib=invite(b),ic=invite(c);try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> jb=()->{gate.await();try{groups.join(b,g,ib,new GroupController.Join(true));return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.GROUP_LIMITED);return false;}};
            Callable<Boolean> jc=()->{gate.await();try{groups.join(c,g,ic,new GroupController.Join(true));return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.GROUP_LIMITED);return false;}};
            var one=pool.submit(jb);var two=pool.submit(jc);gate.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select count(*) from study_group_member where group_id=? and active",Integer.class,g)).isEqualTo(50);
    }
}
