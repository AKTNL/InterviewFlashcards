package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.admin.*;
import com.interviewflashcards.auth.*;
import com.interviewflashcards.backup.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.learning.*;
import java.nio.file.*;
import java.sql.*;
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
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardIT {
    @TempDir static Path directory;
    static class TestClock extends Clock {
        volatile Instant value=Instant.parse("2026-11-02T04:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @TestConfiguration static class TimeOverride { @Bean @Primary TestClock usageTestClock(){return new TestClock();} }
    final String schema="dashboard_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;JdbcTemplate jdbc;ObjectMapper mapper;MockMvc mvc;TestClock clock;UsageService usage;DashboardService dashboard;LearningService learning;UUID admin,b,c;
    @BeforeAll void start(){
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class,TimeOverride.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username=dashboard-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);clock=app.getBean(TestClock.class);usage=app.getBean(UsageService.class);dashboard=app.getBean(DashboardService.class);learning=app.getBean(LearningService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop()throws Exception{if(app!=null)app.close();try(var cn=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=cn.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}}
    UUID account(String role){UUID id=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash,role) values(?,?,?,?)",id,"u-"+id,new BCryptPasswordEncoder(4).encode("test-only-password"),role);return id;}
    @BeforeEach void seed(){jdbc.update("delete from usage_stream");jdbc.update("delete from usage_login");jdbc.update("delete from usage_daily");jdbc.update("delete from usage_user");jdbc.update("update usage_collection set started_at='2026-11-01T00:00:00Z'");clock.value=Instant.parse("2026-11-02T04:00:00Z");admin=account("ADMIN");b=account("USER");c=account("USER");}
    AccountPrincipal principal(UUID user){return new AccountPrincipal(user,jdbc.queryForObject("select username from app_user where id=?",String.class,user),"unused",jdbc.queryForObject("select credential_version from app_user where id=?",Long.class,user));}
    LocalDate day(){return clock.value.atZone(UsageService.ZONE).toLocalDate();}
    JsonNode json(Object value){return mapper.valueToTree(value);}
    JsonNode metrics(UUID id){return json(dashboard.users(admin,day(),day(),principal(id).getUsername(),"duration",0,10)).path("items").get(0);}
    double seconds(UUID id){return jdbc.queryForObject("select coalesce(sum(seconds),0) from usage_daily where user_id=?",Double.class,id);}
    void at(String value){clock.value=Instant.parse(value);}
    UUID stream(){return UUID.randomUUID();}
    void beat(UUID user,String auth,UUID stream,long seq){usage.heartbeat(principal(user),auth,stream,seq,true);}
    @Test void firstHeartbeatIsOnlyAnAnchorAndReplaysRateLimitsAndGapsDoNotInventDuration(){
        UUID stream=stream();long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,b);
        beat(b,"device-a",stream,1);assertThat(seconds(b)).isZero();assertThat(metrics(b).path("online").asBoolean()).isTrue();
        clock.value=clock.value.plusSeconds(30);beat(b,"device-a",stream,2);assertThat(seconds(b)).isEqualTo(30);
        clock.value=clock.value.plusSeconds(30);beat(b,"device-a",stream,2);assertThat(seconds(b)).isEqualTo(30); // response-loss retry
        beat(b,"device-a",stream,3);assertThat(seconds(b)).isEqualTo(60);
        clock.value=clock.value.plusSeconds(5);assertThat(usage.heartbeat(principal(b),"device-a",stream,4,true)).isFalse();
        clock.value=clock.value.plusSeconds(70);beat(b,"device-a",stream,5);assertThat(seconds(b)).isEqualTo(60);
        clock.value=clock.value.plusSeconds(91);assertThat(metrics(b).path("online").asBoolean()).isFalse();assertThat(seconds(b)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,b)).isEqualTo(revision);
        assertThat(metrics(c).path("active_days").asInt()).isZero();assertThat(metrics(c).path("seconds").asDouble()).isZero();
    }
    @Test void concurrentTabsAndDevicesAreUnionedAndDifferentUsersRemainIndependent()throws Exception{
        UUID one=stream(),two=stream();beat(b,"device-a",one,1);clock.value=clock.value.plusSeconds(5);beat(b,"device-b",two,1);clock.value=clock.value.plusSeconds(25);
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();beat(b,"device-a",one,2);return true;});var d=pool.submit(()->{gate.await();beat(b,"device-b",two,2);return true;});gate.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();assertThat(d.get(10,TimeUnit.SECONDS)).isTrue();}
        assertThat(seconds(b)).isEqualTo(30);UUID other=stream();beat(c,"third-device",other,1);clock.value=clock.value.plusSeconds(30);beat(c,"third-device",other,2);
        assertThat(seconds(c)).isEqualTo(30);assertThat(json(dashboard.overview(admin,day(),day())).at("/summary/seconds").asDouble()).isEqualTo(60);
    }
    @Test void shanghaiMidnightSplitsTheUnionAndNeverUsesUtcCalendarDays(){
        at("2026-11-01T15:59:45Z");UUID s=stream();beat(b,"phone",s,1);at("2026-11-01T16:00:15Z");beat(b,"phone",s,2);
        var days=json(dashboard.user(admin,b,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02"))).path("days");
        assertThat(days.get(0).path("seconds").asDouble()).isEqualTo(15);assertThat(days.get(1).path("seconds").asDouble()).isEqualTo(15);assertThat(seconds(b)).isEqualTo(30);
    }
    @Test void stopsCannotBeReopenedByDelayedHeartbeatsAndRevokedAccountsImmediatelyLeaveOnline(){
        UUID s=stream();beat(b,"tab",s,1);clock.value=clock.value.plusSeconds(30);beat(b,"tab",s,2);usage.heartbeat(principal(b),"tab",s,0,false);
        clock.value=clock.value.plusSeconds(30);beat(b,"tab",s,3);assertThat(metrics(b).path("online").asBoolean()).isFalse();assertThat(seconds(b)).isEqualTo(30);
        UUID fresh=stream();beat(b,"phone",fresh,1);jdbc.update("update app_user set enabled=false,credential_version=credential_version+1 where id=?",b);
        assertThat(metrics(b).path("online").asBoolean()).isFalse();assertThatThrownBy(()->beat(b,"phone",fresh,2)).isInstanceOf(ApiFailure.class);
        jdbc.update("update app_user set enabled=true where id=?",b);assertThat(metrics(b).path("online").asBoolean()).isFalse();
    }
    @Test void httpRequiresSessionCsrfAndStrictMetadataAndAdminQueriesNeverRevealPrivateContent()throws Exception{
        String range="?from=2026-11-02&to=2026-11-02";
        mvc.perform(get("/api/admin/dashboard"+range)).andExpect(status().isUnauthorized());
        for(String path:List.of("/api/admin/dashboard","/api/admin/dashboard/users","/api/admin/dashboard/users/"+b))mvc.perform(get(path+range).with(user(principal(c)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/usage/heartbeat").with(user(principal(b))).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        String body="{\"streamId\":\""+stream()+"\",\"sequence\":1,\"active\":true}";
        mvc.perform(post("/api/usage/heartbeat").with(user(principal(b))).with(csrf()).contentType("application/json").content(body.replace("}",",\"seconds\":99999}"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/usage/heartbeat").with(user(principal(b))).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        String response=mvc.perform(get("/api/admin/dashboard/users"+range).with(user(principal(admin)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password","credential","auth_hash","private-credential","sessionId");
        mvc.perform(get("/api/admin/dashboard?from=2026-11-03&to=2026-11-02").with(user(principal(admin)))).andExpect(status().isBadRequest());
        jdbc.update("update app_user set role='USER' where id=?",admin);mvc.perform(get("/api/admin/dashboard"+range).with(user(principal(admin)))).andExpect(status().isForbidden());
    }
    @Test void realLogoutRetiresOnlyItsOwnDevicePresence()throws Exception{
        var login=mvc.perform(post("/api/auth/login").with(csrf()).param("username",principal(b).getUsername()).param("password","test-only-password")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);UUID s=stream();beat(b,session.getId(),s,1);
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());assertThat(metrics(b).path("online").asBoolean()).isFalse();
        clock.value=clock.value.plusSeconds(5);UUID phone=stream();beat(b,"phone",phone,1);usage.endSession("already-logged-out");assertThat(metrics(b).path("online").asBoolean()).isTrue();
        assertThatThrownBy(()->beat(b,"already-logged-out",stream(),1)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
    @Test void beforeCollectionIsNullZeroIsRealAndCleanupPreservesDailyTotals(){
        var history=json(dashboard.overview(admin,LocalDate.parse("2026-10-31"),LocalDate.parse("2026-10-31")));
        assertThat(history.path("activityCoverage").asText()).isEqualTo("UNCOLLECTED");assertThat(history.at("/summary/seconds").isNull()).isTrue();assertThat(history.at("/days/0/seconds").isNull()).isTrue();
        UUID s=stream();beat(b,"phone",s,1);clock.value=clock.value.plusSeconds(30);beat(b,"phone",s,2);clock.value=clock.value.plus(Duration.ofDays(31));usage.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from usage_stream",Long.class)).isZero();assertThat(jdbc.queryForObject("select spans is null from usage_daily where user_id=?",Boolean.class,b)).isTrue();assertThat(seconds(b)).isEqualTo(30);
        clock.value=clock.value.plus(Duration.ofDays(370));usage.cleanup();assertThat(jdbc.queryForObject("select count(*) from usage_daily",Long.class)).isZero();
    }
    UUID epoch(){return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,b);}
    UUID card()throws Exception{
        UUID source=stream(),deck=stream(),module=stream(),chapter=stream(),job=stream(),draft=stream(),card=stream(),key=stream();
        Path file=directory.resolve("files").resolve(key.toString());
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.getDocumentInformation().setSubject(key.toString());pdf.save(file.toFile());}
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'test.pdf',?,1,?,?)",source,b,BackupArchive.hash(file),Files.size(file),key);
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",deck,b);jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,b,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'Java','Java','IMPORTED')",chapter,b,module);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id) values(?,?,?,'READY',?)",job,b,source,deck);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'q','private-question')",draft,b,chapter,job);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'private-question')",card,b,chapter,draft);return card;
    }
    JsonNode rate(UUID card)throws Exception{
        var session=learning.start(b,new LearningController.Start(stream(),epoch(),List.of(card))).path("session");UUID id=UUID.fromString(session.path("id").asText());
        var payload=mapper.createObjectNode().put("itemId",session.at("/current/id").asText()).put("expectedReviewVersion",session.at("/current/expectedVersion").asLong()).put("answerRevealed",true).put("confirmEarly",true).put("rating","REMEMBERED");
        var result=learning.write(b,id,new LearningController.Write(stream(),epoch(),session.path("version").asLong(),"RATE",payload));
        jdbc.update("update review_event set created_at=? where user_id=? and id=?",Timestamp.from(clock.value),b,UUID.fromString(result.path("eventId").asText()));return result;
    }
    byte[] backup()throws Exception{var service=app.getBean(BackupService.class);UUID id=UUID.fromString(service.createExport(b,stream()).path("id").asText());service.export(b,id);return service.download(b,id).getContentAsByteArray();}
    void restore(byte[] bytes)throws Exception{var service=app.getBean(BackupService.class);UUID id=UUID.fromString(service.upload(b,stream(),new MockMultipartFile("file","data.zip","application/zip",bytes)).path("id").asText());service.validate(b,id);var preview=service.job(b,id,true).path("validation_result");service.confirm(b,id,UUID.fromString(preview.path("expectedEpoch").asText()),preview.path("expectedRevision").asLong(),true);service.restore(b,id);}
    @Test void effectiveLearningMatchesReplayUndoAndActualZipRestoreWithoutRollingBackActivity()throws Exception{
        UUID one=card();rate(one);rate(one);assertThat(metrics(b).path("new_count").asInt()).isOne();assertThat(metrics(b).path("review_count").asInt()).isZero();
        byte[] zip=backup();UUID second=card();var rated=rate(second);assertThat(metrics(b).path("new_count").asInt()).isEqualTo(2);
        var session=rated.path("session");learning.write(b,UUID.fromString(session.path("id").asText()),new LearningController.Write(stream(),epoch(),session.path("version").asLong(),"UNDO",mapper.createObjectNode().put("eventId",rated.path("eventId").asText())));
        assertThat(metrics(b).path("new_count").asInt()).isOne();rate(second);assertThat(metrics(b).path("new_count").asInt()).isEqualTo(2);
        UUID stream=stream();beat(b,"browser",stream,1);clock.value=clock.value.plusSeconds(30);beat(b,"browser",stream,2);restore(zip);
        assertThat(metrics(b).path("new_count").asInt()).isOne();assertThat(seconds(b)).isEqualTo(30);assertThat(metrics(b).path("online").asBoolean()).isTrue();
        assertThat(metrics(b).path("new_count").asLong()).isEqualTo(((Number)learning.summary(b).get("todayNew")).longValue());
        assertThat(json(dashboard.user(admin,b,day(),day())).toString()).doesNotContain("private-question","test.pdf");
        clock.value=clock.value.plus(Duration.ofDays(1));rate(jdbc.queryForObject("select id from card where user_id=?",UUID.class,b));assertThat(metrics(b).path("review_count").asInt()).isOne();assertThat(metrics(b).path("new_count").asInt()).isZero();
    }
    @Test void learningUsesEventTimestampInsteadOfUsersLearningDayAndQueriesPaginateAndSort()throws Exception{
        UUID card=card();var event=rate(card);jdbc.update("update review_event set created_at='2026-11-01T16:30:00Z',learning_date='2026-11-01' where id=?",UUID.fromString(event.path("eventId").asText()));
        assertThat(metrics(b).path("new_count").asInt()).isOne();UUID s=stream();beat(c,"phone",s,1);clock.value=clock.value.plusSeconds(30);beat(c,"phone",s,2);
        var sorted=json(dashboard.users(admin,day(),day(),"","duration",0,1));assertThat(sorted.path("items").get(0).path("id").asText()).isEqualTo(c.toString());assertThat(sorted.path("items").size()).isOne();
        var next=json(dashboard.users(admin,day(),day(),"","duration",1,1));assertThat(next.path("items").get(0).path("id").asText()).isNotEqualTo(c.toString());
        assertThat(metrics(b).path("storage_bytes").asLong()).isEqualTo(jdbc.queryForObject("select sum(byte_size) from source_document where user_id=?",Long.class,b));
    }
}
