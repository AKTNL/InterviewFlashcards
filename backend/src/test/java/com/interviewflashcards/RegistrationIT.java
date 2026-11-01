package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.CapacityService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistrationIT {
    @TempDir static Path directory;
    final String schema="registration_it_"+UUID.randomUUID().toString().replace("-", "");
    final String original="legacy-password-test", replacement="replacement-password-test";
    final UUID legacy=UUID.randomUUID();
    ServletWebServerApplicationContext app;
    JdbcTemplate jdbc; MockMvc mvc; ObjectMapper mapper;
    @BeforeAll void migrateLegacy() throws Exception {
        Flyway.configure().dataSource(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"))
            .schemas(schema).defaultSchema(schema).target("10").load().migrate();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"))) {
            c.setSchema(schema);
            try(var s=c.prepareStatement("insert into app_user(id,username,password_hash,revision) values(?,'Legacy.Owner',?,17)")) {
                s.setObject(1,legacy);s.setString(2,new BCryptPasswordEncoder(10).encode(original));s.executeUpdate();
            }
        }
        start(true);
    }
    void start(boolean enabled) {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username="+(enabled?"Legacy.Owner":"Changed.Environment"),"--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("environment-password-ignored"),
            "--app.registration.enabled="+enabled,"--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--app.capacity.user-bytes=1048576","--app.capacity.sources=1","--app.capacity.imports=1","--app.capacity.backups=1",
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void cleanup() throws Exception {
        if(app!=null) app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()) { s.execute("drop schema if exists "+schema+" cascade"); }
    }
    String invite(int uses) {
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        jdbc.update("insert into registration_invite(id,token_digest,expires_at,max_uses) values(?,?,current_timestamp+interval '1 day',?)",UUID.randomUUID(),AccountRules.digest(token),uses);
        return token;
    }
    MvcResult register(String token,String name) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("invite",token,"username",name,"password",original,"nickname","朋友")))).andReturn();
    }
    MockHttpSession login(String name,String password) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password",password)).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession();
    }
    void code(MvcResult result,int status,String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()).at("/error/code").asText()).isEqualTo(code);
    }
    @Test @Order(1) void upgradePreservesExactLegacyNameIdentityAndCredentials() throws Exception {
        mvc.perform(get("/api/auth/me").session(login("Legacy.Owner",original))).andExpect(jsonPath("$.data.id").value(legacy.toString()));
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,legacy)).isEqualTo(17);
        assertThat(jdbc.queryForObject("select user_id from preset_identity",UUID.class)).isEqualTo(legacy);
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String token=invite(3);
        assertThat(register(token," Alice_01 ").getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()); // No implicit login.
        var desktop=login("alice_01",original); var mobile=login("alice_01",original);
        mvc.perform(get("/api/auth/me").session(desktop)).andExpect(jsonPath("$.data.nickname").value("朋友"));
        mvc.perform(get("/api/auth/me").session(mobile)).andExpect(jsonPath("$.data.username").value("alice_01"));
        code(register(token,"ALICE_01"),409,"USERNAME_TAKEN");
        code(register(token,"root"),400,"ACCOUNT_NAME_INVALID");
        assertThat(jdbc.queryForObject("select used_count from registration_invite where token_digest=?",Integer.class,AccountRules.digest(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from learning_preference p join app_user u on u.id=p.user_id where u.username='alice_01'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(distinct data_epoch) from app_user",Integer.class)).isEqualTo(2);
    }
    @Test @Order(2) void invalidInvitesAndConcurrentLastSeatNeverLeavePartialAccounts() throws Exception {
        String expired=invite(1),revoked=invite(1),exhausted=invite(1);
        jdbc.update("update registration_invite set expires_at=current_timestamp-interval '1 second' where token_digest=?",AccountRules.digest(expired));
        jdbc.update("update registration_invite set revoked=true where token_digest=?",AccountRules.digest(revoked));
        jdbc.update("update registration_invite set used_count=max_uses where token_digest=?",AccountRules.digest(exhausted));
        for(String token:List.of(expired,revoked,exhausted,"x".repeat(43))) code(register(token,"no_account"),400,"INVITE_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from app_user where username='no_account'",Integer.class)).isZero();
        String token=invite(1);var service=app.getBean(RegistrationService.class);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Boolean>>();
            for(String name:List.of("concurrent_a","concurrent_b")) results.add(pool.submit(()->{
                barrier.await();try { service.register(token,name,original,null); return true; } catch(ApiFailure e) { assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID);return false; }
            }));
            assertThat(results.get(0).get(20,TimeUnit.SECONDS)^results.get(1).get(20,TimeUnit.SECONDS)).isTrue();
        }
        String duplicate=invite(1);
        code(register(duplicate,"alice_01"),409,"USERNAME_TAKEN");
        assertThat(jdbc.queryForObject("select used_count from registration_invite where token_digest=?",Integer.class,AccountRules.digest(duplicate))).isZero();
        assertThat(register(duplicate,"bob_01").getResponse().getStatus()).isEqualTo(200);
        String left=invite(1),right=invite(1);var start=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Boolean>>();
            for(String invitation:List.of(left,right)) results.add(pool.submit(()-> {
                start.await();try {service.register(invitation,"same_name",original,null);return true;}
                catch(ApiFailure e) {assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN);return false;}
            }));
            assertThat(results.get(0).get(20,TimeUnit.SECONDS)^results.get(1).get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select sum(used_count) from registration_invite where token_digest in (?,?)",Integer.class,AccountRules.digest(left),AccountRules.digest(right))).isEqualTo(1);
    }
    @Test @Order(3) void uploadQuotasAreServerSideDeduplicateAndKeepAccountsIndependent() throws Exception {
        var a=login("alice_01",original);var b=login("bob_01",original);
        byte[] one,two;
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(out);one=out.toByteArray();
            out.reset();doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(out);two=out.toByteArray();
        }
        String source=null;
        for(var session:List.of(a,b)) {
            String epoch=mapper.readTree(mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getContentAsByteArray()).at("/data/dataEpoch").asText();
            for(int i=0;i<2;i++) {
                var result=mvc.perform(multipart("/api/sources").file(new org.springframework.mock.web.MockMultipartFile("file","test.pdf","application/pdf",one))
                    .session(session).with(csrf()).param("operationId",UUID.randomUUID().toString()).param("dataEpoch",epoch)).andExpect(status().isOk()).andReturn();
                if(session==a) source=mapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/source/id").asText();
            }
            mvc.perform(multipart("/api/sources").file(new org.springframework.mock.web.MockMultipartFile("file","other.pdf","application/pdf",two))
                .session(session).with(csrf()).param("operationId",UUID.randomUUID().toString()).param("dataEpoch",epoch))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));
            mvc.perform(get("/api/storage/usage").session(session)).andExpect(jsonPath("$.data.sources").value(1));
        }
        mvc.perform(get("/api/sources/"+source+"/content").session(b)).andExpect(status().isNotFound());
        UUID owner=jdbc.queryForObject("select id from app_user where username='alice_01'",UUID.class);
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(()->tx.execute(s->{app.getBean(CapacityService.class).check(owner,0,false,"import");return null;}))
            .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.TASK_LIMITED));
    }
    @Test @Order(3) void accountBindingAndResourceOwnershipAreEnforcedOnServer() throws Exception {
        var a=login("alice_01",original);var b=login("bob_01",original);
        String aid=jdbc.queryForObject("select id::text from app_user where username='alice_01'",String.class);
        mvc.perform(get("/api/sync/snapshot").session(b).header("X-Account-ID",aid)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_CHANGED"));
        mvc.perform(post("/api/backups").session(b).with(csrf()).header("X-Account-ID",aid).contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isConflict());
        String bEpoch=jdbc.queryForObject("select data_epoch::text from app_user where username='bob_01'",String.class);
        UUID export=UUID.fromString(mapper.readTree(mvc.perform(post("/api/backups").session(a).with(csrf()).contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).at("/data/id").asText());
        for(String path:List.of("/api/backups/"+export,"/api/backups/"+export+"/download","/api/sources/"+UUID.randomUUID()+"/content","/api/assets/"+UUID.randomUUID()+"/content","/api/sync/receipts/"+UUID.randomUUID()+"?dataEpoch="+bEpoch))
            mvc.perform(get(path).session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/storage/usage").session(a)).andExpect(jsonPath("$.data.maxSources").value(1)).andExpect(jsonPath("$.data.maxBytes").value(1048576));
        mvc.perform(post("/api/backups").session(a).with(csrf()).contentType("application/json").content("{\"operationId\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("TASK_LIMITED"));
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(()->tx.execute(s-> {app.getBean(CapacityService.class).check(UUID.fromString(aid),1048577,false,null);return null;}))
            .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
    }
    @Test @Order(4) void passwordChangeRevokesAllSessionsAndSurvivesRestart() throws Exception {
        var desktop=login("Legacy.Owner",original);var mobile=login("Legacy.Owner",original);
        mvc.perform(post("/api/auth/password").session(desktop).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("currentPassword","wrong","newPassword",replacement))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));
        mvc.perform(post("/api/auth/password").session(desktop).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("currentPassword",original,"newPassword",replacement))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(mobile)).andExpect(status().isUnauthorized());
        String validated=invite(1);app.getBean(PersonalInviteService.class).validate(validated);
        app.close();start(false);
        mvc.perform(post("/api/auth/invite/validate").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("invite",validated))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REGISTRATION_CLOSED"));
        code(register(validated,"closed_after_validation"),403,"REGISTRATION_CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from app_user where username='Changed.Environment'",Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").session(login("Legacy.Owner",replacement))).andExpect(jsonPath("$.data.id").value(legacy.toString()));
        code(register(invite(1),"closed_account"),403,"REGISTRATION_CLOSED");
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,legacy)).isEqualTo(17);
    }
    @Test @Order(5) void loginThrottlesByAccountAndTrustedSourceAndIgnoresSpoofedForwardedHeaders() throws Exception {
        jdbc.update("delete from auth_rate_bucket");
        for(int i=0;i<20;i++) mvc.perform(post("/api/auth/login").with(csrf()).param("username","missing").param("password","incorrect"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).header("X-Forwarded-For","1.2.3.4").param("username","missing").param("password","incorrect"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        jdbc.update("update auth_rate_bucket set window_start=current_timestamp-interval '16 minutes'");
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","missing").param("password","incorrect"))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select max(attempts) from auth_rate_bucket",Integer.class)).isEqualTo(1);
        jdbc.update("delete from auth_rate_bucket");
        for(int i=0;i<20;i++) code(register("x".repeat(43),"closed_"+i),403,"REGISTRATION_CLOSED");
        code(register("x".repeat(43),"closed_limit"),429,"RATE_LIMITED");
    }
}
