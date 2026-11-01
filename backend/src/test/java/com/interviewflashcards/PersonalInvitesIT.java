package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.*;
import com.interviewflashcards.backup.BackupService;
import com.interviewflashcards.common.web.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
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
class PersonalInvitesIT {
    @TempDir static Path directory;
    final String schema="personal_invites_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;JdbcTemplate jdbc;ObjectMapper mapper;MockMvc mvc;
    PersonalInviteService invites;RegistrationService registrations; UUID a,b;int names;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.registration.enabled=true","--app.registration.max-accounts=3",
            "--app.account.username=personal-invites-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);invites=app.getBean(PersonalInviteService.class);registrations=app.getBean(RegistrationService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var cn=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=cn.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account() {UUID id=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'private-credential')",id,"u-"+id);return id;}
    @BeforeEach void seed() {
        jdbc.update("delete from registration_use");jdbc.update("delete from auth_rate_bucket");a=account();b=account();
        jdbc.update("insert into invitation_permission(user_id,enabled,total_limit) values(?,true,2)",a);
    }
    String name(){return "new_user_"+(++names);}
    void register(String token,String name){registrations.register(token,name,"test-only-password",null);}
    AccountPrincipal principal(UUID id){return new AccountPrincipal(id,"test","unused");}
    interface Work {void run() throws Exception;}
    void error(Work action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    @Test void generationIsOwnedMaskedSingleUseAndPermissionsAreServerEnforced() throws Exception {
        error(()->invites.generate(b),ErrorCode.INVITATION_NOT_ALLOWED);
        var generated=invites.generate(a);String token=generated.token();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");assertThat(generated.toString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("select token_digest from registration_invite where id=?",String.class,generated.invite().id())).isEqualTo(AccountRules.digest(token));
        assertThat(mapper.writeValueAsString(invites.overview(a))).doesNotContain(token,"token_digest","username");
        error(()->invites.generate(a),ErrorCode.INVITE_ALREADY_ACTIVE);error(()->invites.revoke(b,generated.invite().id()),ErrorCode.NOT_FOUND);
        assertThat(invites.overview(b).latest()).isNull();
        invites.revoke(a,generated.invite().id());assertThat(invites.overview(a).remainingQuota()).isEqualTo(2);
        error(()->invites.validate(token),ErrorCode.INVITE_INVALID);assertThat(invites.generate(a).token()).isNotEqualTo(token);
    }
    @Test void validationConsumesNothingAndCannotAuthenticateOrReadProtectedApis() throws Exception {
        var generated=invites.generate(a);
        mvc.perform(post("/api/auth/invite/validate").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("invite",generated.token()))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.data").value(true));
        assertThat(invites.overview(a).successfulCount()).isZero();
        for(String path:List.of("/api/auth/me","/api/account/invites","/api/files","/api/sync/state","/api/backups"))mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/invite/validate").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/account/invites").with(user(principal(b))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/account/invites/"+generated.invite().id()+"/revoke").with(user(principal(b))).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/account/invites").with(user(principal(a)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void finalRegistrationRechecksRevocationExpiryAndInviterPermission() {
        var revoked=invites.generate(a);invites.validate(revoked.token());
        invites.revoke(a,revoked.invite().id());error(()->register(revoked.token(),name()),ErrorCode.INVITE_INVALID);
        var generated=invites.generate(a);String token=generated.token();
        jdbc.update("update registration_invite set expires_at=current_timestamp-interval '1 second' where id=?",generated.invite().id());
        error(()->register(token,name()),ErrorCode.INVITE_INVALID);assertThat(invites.overview(a).latest().status()).isEqualTo("EXPIRED");
        var next=invites.generate(a);jdbc.update("update invitation_permission set enabled=false where user_id=?",a);
        error(()->register(next.token(),name()),ErrorCode.INVITE_INVALID);
        assertThat(invites.overview(a).successfulCount()).isZero();
    }
    @Test void successfulQuotaIsPermanentAndNewAccountsHaveNoInvitationPermission() {
        String token=invites.generate(a).token(),username=name();register(token,username);
        assertThat(invites.overview(a).successfulCount()).isOne();assertThat(invites.overview(a).latest().status()).isEqualTo("USED");
        error(()->register(token,name()),ErrorCode.INVITE_INVALID);
        UUID registered=jdbc.queryForObject("select id from app_user where username=?",UUID.class,username);
        assertThat(invites.overview(registered).allowed()).isFalse();error(()->invites.generate(registered),ErrorCode.INVITATION_NOT_ALLOWED);
        register(invites.generate(a).token(),name());assertThat(invites.overview(a).remainingQuota()).isZero();
        error(()->invites.generate(a),ErrorCode.INVITATION_NOT_ALLOWED);
        assertThat(jdbc.queryForObject("select count(*) from app_user where username=?",Integer.class,username)).isOne();
    }
    @Test void usernameConflictAndMidTransactionFailureDoNotConsumeAnyQuota() {
        var generated=invites.generate(a);String username=name();register(generated.token(),username);
        var next=invites.generate(a);error(()->register(next.token(),username),ErrorCode.USERNAME_TAKEN);
        assertThat(invites.overview(a).successfulCount()).isOne();
        jdbc.execute("create function fail_invitation_use() returns trigger language plpgsql as $$ begin raise exception 'injected rollback'; end $$");
        jdbc.execute("create trigger fail_invitation_use before insert on registration_use for each row execute function fail_invitation_use()");
        String failed=name();try {assertThatThrownBy(()->register(next.token(),failed)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally {jdbc.execute("drop trigger fail_invitation_use on registration_use");jdbc.execute("drop function fail_invitation_use()");}
        assertThat(jdbc.queryForObject("select count(*) from app_user where username=?",Integer.class,failed)).isZero();
        assertThat(invites.overview(a).successfulCount()).isOne();assertThat(invites.overview(a).latest().usedCount()).isZero();
    }
    List<Boolean> race(Callable<Boolean> first,Callable<Boolean> second) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f=pool.submit(()->{barrier.await();return first.call();});var s=pool.submit(()->{barrier.await();return second.call();});
            return List.of(f.get(30,TimeUnit.SECONDS),s.get(30,TimeUnit.SECONDS));
        }
    }
    @Test void concurrentGenerationCannotProduceTwoActiveInvites() throws Exception {
        Callable<Boolean> generate=()->{try{invites.generate(a);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.INVITE_ALREADY_ACTIVE);return false;}};
        assertThat(race(generate,generate)).containsExactlyInAnyOrder(true,false);
        assertThat(jdbc.queryForObject("select count(*) from registration_invite where inviter_id=?",Integer.class,a)).isOne();
    }
    @Test void concurrentLastInviteSeatAndRevocationHaveDeterministicOutcomes() throws Exception {
        var generated=invites.generate(a);String first=name(),second=name();
        var results=race(()->{try{register(generated.token(),first);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID);return false;}},
            ()->{try{register(generated.token(),second);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID);return false;}});
        assertThat(results).containsExactlyInAnyOrder(true,false);assertThat(invites.overview(a).successfulCount()).isOne();
        var next=invites.generate(a);String third=name();
        results=race(()->{try{register(next.token(),third);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID);return false;}},()->{invites.revoke(a,next.invite().id());return true;});
        assertThat(invites.overview(a).successfulCount()).isEqualTo(results.getFirst()?2:1);
        error(()->invites.validate(next.token()),ErrorCode.INVITE_INVALID);
    }
    String maintenance() {
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        jdbc.update("insert into registration_invite(id,token_digest,expires_at,max_uses) values(?,?,current_timestamp+interval '1 day',10)",UUID.randomUUID(),AccountRules.digest(token));return token;
    }
    @Test void globalLastSeatAcrossDifferentInvitesIncludesMaintenanceInvitations() throws Exception {
        String old=maintenance();register(old,name());register(old,name());
        var personal=invites.generate(a);String first=name(),second=name();
        var results=race(()->{try{register(old,first);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.REGISTRATION_LIMITED);return false;}},
            ()->{try{register(personal.token(),second);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.REGISTRATION_LIMITED);return false;}});
        assertThat(results).containsExactlyInAnyOrder(true,false);assertThat(jdbc.queryForObject("select count(*) from registration_use",Integer.class)).isEqualTo(3);
        error(()->invites.validate(old),ErrorCode.REGISTRATION_LIMITED);error(()->invites.generate(a),ErrorCode.REGISTRATION_LIMITED);
    }
    @Test void validationAndRegistrationShareRateLimitAndGenerationHasAnAccountLimit() throws Exception {
        String body=mapper.writeValueAsString(Map.of("invite","x".repeat(43)));
        for(int i=0;i<20;i++)mvc.perform(post("/api/auth/invite/validate").with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isTooManyRequests());
        for(int i=0;i<20;i++)mvc.perform(post("/api/account/invites").with(user(principal(b))).with(csrf()).with(r-> {r.setRemoteAddr("192.0.2."+names++);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/api/account/invites").with(user(principal(b))).with(csrf())).andExpect(status().isTooManyRequests());
    }
    @Test void personalZipRoundTripCannotRestoreInvitesOrRefundUsedQuota() throws Exception {
        var generated=invites.generate(a);var backups=app.getBean(BackupService.class);
        UUID id=UUID.fromString(backups.createExport(a,UUID.randomUUID()).path("id").asText());backups.export(a,id);byte[] bytes=backups.download(a,id).getContentAsByteArray();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(generated.token(),"invitation_permission","registration_invite");
        }
        register(generated.token(),name());var next=invites.generate(a);invites.revoke(a,next.invite().id());
        UUID restore=UUID.fromString(backups.upload(a,UUID.randomUUID(),new MockMultipartFile("file","invites.zip","application/zip",bytes)).path("id").asText());
        backups.validate(a,restore);var p=backups.job(a,restore,true).path("validation_result");backups.confirm(a,restore,UUID.fromString(p.path("expectedEpoch").asText()),p.path("expectedRevision").asLong(),true);backups.restore(a,restore);
        assertThat(invites.overview(a).successfulCount()).isOne();assertThat(invites.overview(a).latest().status()).isEqualTo("REVOKED");assertThat(invites.overview(a).allowed()).isTrue();
    }
}
