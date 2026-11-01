package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.admin.*;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminIT {
    @TempDir static Path directory;
    final String schema="admin_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;JdbcTemplate jdbc;ObjectMapper mapper;MockMvc mvc;
    AdminService admins;PersonalInviteService invites;RegistrationService registrations;UUID admin,a;int sequence;
    final String password="test-initial-password", replacement="test-replacement-password";
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(10);
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.registration.enabled=true","--app.registration.max-accounts=3",
            "--app.account.username=admin-test-preset","--app.account.password-hash="+encoder.encode(password),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);admins=app.getBean(AdminService.class);invites=app.getBean(PersonalInviteService.class);registrations=app.getBean(RegistrationService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var cn=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=cn.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account(String role) {UUID id=UUID.randomUUID();String name="user_"+(++sequence);jdbc.update("insert into app_user(id,username,password_hash,role) values(?,?,?,?)",id,name,encoder.encode(password),role);jdbc.update("insert into account_name(normalized,user_id) values(?,?)",name,id);return id;}
    @BeforeEach void seed() {
        jdbc.update("delete from registration_use");jdbc.update("delete from admin_account_allocation");jdbc.update("delete from auth_rate_bucket");
        jdbc.update("update app_user set role='USER'");admin=account("ADMIN");a=account("USER");
        jdbc.update("insert into invitation_permission(user_id,enabled,total_limit) values(?,true,3)",a);
    }
    String name(){return "created_"+(++sequence);}
    String username(UUID id){return jdbc.queryForObject("select username from app_user where id=?",String.class,id);}
    long version(UUID id){return jdbc.queryForObject("select admin_version from app_user where id=?",Long.class,id);}
    AccountPrincipal principal(UUID id){return new AccountPrincipal(id,username(id),"unused",jdbc.queryForObject("select credential_version from app_user where id=?",Long.class,id));}
    interface Work{void run()throws Exception;}
    void error(Work action,ErrorCode code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    MockHttpSession login(UUID id,String secret)throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username(id)).param("password",secret)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void migrationDefaultsKeepPresetIdentityCredentialsAndLearningSpace() {
        var preset=jdbc.queryForMap("select u.* from app_user u join preset_identity p on p.user_id=u.id");
        assertThat(preset.get("role")).isEqualTo("USER");assertThat(preset.get("enabled")).isEqualTo(true);
        assertThat(encoder.matches(password,(String)preset.get("password_hash"))).isTrue();
        assertThat(app.getBean(AccountService.class).profile(principal(admin)).capabilities()).contains(Capabilities.USERS);
    }
    @Test void upgradesExistingV14AccountsWithoutChangingIdentityPasswordOrInviteCounts() throws Exception {
        String upgrade=schema+"_upgrade";
        String url=System.getenv("DATABASE_URL"),dbUser=System.getenv("DATABASE_USERNAME"),dbPassword=System.getenv("DATABASE_PASSWORD");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(url,dbUser,dbPassword).schemas(upgrade).defaultSchema(upgrade).target("14").load().migrate();
            UUID legacy=UUID.randomUUID();String hash=encoder.encode(password);
            try(var cn=DriverManager.getConnection(url,dbUser,dbPassword)) {
                cn.setSchema(upgrade);
                try(var q=cn.prepareStatement("insert into app_user(id,username,password_hash,account_origin) values(?,'Legacy.Exact',?,'PRESET')")){q.setObject(1,legacy);q.setString(2,hash);q.executeUpdate();}
                try(var q=cn.prepareStatement("insert into preset_identity(id,user_id) values(1,?)")){q.setObject(1,legacy);q.executeUpdate();}
                try(var q=cn.prepareStatement("insert into invitation_permission(user_id,enabled,total_limit,successful_count) values(?,true,3,2)")){q.setObject(1,legacy);q.executeUpdate();}
            }
            org.flywaydb.core.Flyway.configure().dataSource(url,dbUser,dbPassword).schemas(upgrade).defaultSchema(upgrade).load().migrate();
            try(var cn=DriverManager.getConnection(url,dbUser,dbPassword)) {
                cn.setSchema(upgrade);
                try(var q=cn.createStatement();var row=q.executeQuery("select u.id,u.username,u.password_hash,u.role,u.enabled,p.user_id,i.successful_count from app_user u join preset_identity p on p.user_id=u.id join invitation_permission i on i.user_id=u.id")) {
                    assertThat(row.next()).isTrue();assertThat(row.getObject(1,UUID.class)).isEqualTo(legacy);assertThat(row.getString(2)).isEqualTo("Legacy.Exact");assertThat(row.getString(3)).isEqualTo(hash);assertThat(row.getString(4)).isEqualTo("USER");assertThat(row.getBoolean(5)).isTrue();assertThat(row.getObject(6,UUID.class)).isEqualTo(legacy);assertThat(row.getInt(7)).isEqualTo(2);
                }
            }
        } finally {try(var cn=DriverManager.getConnection(url,dbUser,dbPassword);var q=cn.createStatement()){q.execute("drop schema if exists "+upgrade+" cascade");}}
    }
    @Test void managementIsDeniedForOrdinaryUsersAndLiveRoleRevocation()throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").with(user(principal(a)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/"+a+"/status").with(user(principal(admin))).contentType("application/json").content("{\"version\":0,\"enabled\":false}")).andExpect(status().isForbidden());
        var session=login(admin,password);
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());
        jdbc.update("update app_user set role='USER' where id=?",admin);
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
    }
    @Test void createIsIdempotentRequiresFirstChangeAndDoesNotInheritPermissions()throws Exception {
        UUID operation=UUID.randomUUID();String name=name();UUID id=admins.create(admin,operation,name,password,"New friend");
        assertThat(admins.create(admin,operation,name,password,"New friend")).isEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from admin_account_allocation",Integer.class)).isOne();
        error(()->admins.create(admin,operation,name,replacement,"New friend"),ErrorCode.OPERATION_ID_REUSED);
        assertThat(jdbc.queryForObject("select count(*) from registration_use where user_id=?",Integer.class,id)).isZero();
        assertThat(invites.overview(id).allowed()).isFalse();
        var session=login(id,password);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.mustChangePassword").value(true)).andExpect(jsonPath("$.data.role").value("USER"));
        for(String path:List.of("/api/files","/api/sync/state","/api/admin/users","/api/backups"))mvc.perform(get(path).session(session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
        error(()->registrations.changePassword(id,password,password),ErrorCode.PASSWORD_REUSED);
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("currentPassword",password,"newPassword",replacement)))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").with(user(new AccountPrincipal(id,name,"unused",0)))).andExpect(status().isUnauthorized());
        login(id,replacement);assertThat(jdbc.queryForObject("select must_change_password from app_user where id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("select last_login_at from app_user where id=?",java.sql.Timestamp.class,id)).isNotNull();
    }
    @Test void statusAndResetRevokeSessionsButKeepDataAndDoNotRestoreInvites()throws Exception {
        var session=login(a,password);var invite=invites.generate(a);UUID epoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,a);
        admins.status(admin,a,0,false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",username(a)).param("password",password)).andExpect(status().isUnauthorized());
        error(()->invites.validate(invite.token()),ErrorCode.INVITE_INVALID);
        admins.status(admin,a,1,true);error(()->invites.validate(invite.token()),ErrorCode.INVITE_INVALID);
        var enabledSession=login(a,password);admins.reset(admin,a,2,replacement);
        mvc.perform(get("/api/auth/me").session(enabledSession)).andExpect(status().isUnauthorized());
        var resetSession=login(a,replacement);mvc.perform(get("/api/sync/state").session(resetSession)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,a)).isEqualTo(epoch);
    }
    @Test void permissionUsesPermanentCountsAndRevokesUnusedInvites() {
        String token=invites.generate(a).token();registrations.register(token,name(),password,null);var unused=invites.generate(a);
        admins.permission(admin,a,0,false,1);error(()->invites.validate(unused.token()),ErrorCode.INVITE_INVALID);
        admins.permission(admin,a,1,true,3);assertThat(invites.overview(a).successfulCount()).isOne();
        error(()->invites.validate(unused.token()),ErrorCode.INVITE_INVALID);
        admins.permission(admin,a,2,true,0);assertThat(invites.overview(a).successfulCount()).isOne();assertThat(invites.overview(a).remainingQuota()).isZero();
        error(()->invites.generate(a),ErrorCode.INVITATION_NOT_ALLOWED);
    }
    @Test void targetVersionStrictFieldsAndOwnershipCannotBeBypassed()throws Exception {
        admins.permission(admin,a,0,true,5);error(()->admins.status(admin,a,0,false),ErrorCode.VERSION_CONFLICT);
        error(()->admins.status(admin,admin,0,false),ErrorCode.FORBIDDEN);
        var other=account("USER");var invite=invites.generate(a);
        error(()->admins.revoke(admin,other,0,invite.invite().id()),ErrorCode.NOT_FOUND);
        mvc.perform(post("/api/admin/users").with(user(principal(admin))).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("operationId",UUID.randomUUID(),"username",name(),"password",password,"role","ADMIN")))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from app_user where role='ADMIN'",Integer.class)).isOne();
        // Administrator's ordinary learning API retains its own data ownership.
        UUID source=UUID.randomUUID();jdbc.update("insert into source_document(id,user_id,original_filename,sha256,byte_size,page_count,storage_key) values(?,?,'private.pdf',?,1,1,?)",source,a,"f".repeat(64),UUID.randomUUID());
        mvc.perform(get("/api/sources/"+source).with(user(principal(admin)))).andExpect(status().isNotFound());
    }
    @Test void voluntaryPasswordChangeInvalidatesOldAdminResetPreview() {
        registrations.changePassword(a,password,replacement);
        error(()->admins.reset(admin,a,0,password),ErrorCode.VERSION_CONFLICT);
        assertThat(encoder.matches(replacement,jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a))).isTrue();
    }
    @Test void userSearchPaginationAndDetailContainOnlyApprovedMetadata()throws Exception {
        jdbc.update("update app_user set nickname='Unique nickname' where id=?",a);
        var result=admins.users(admin,"unique nickname","ENABLED","ENABLED",0,10);
        assertThat((List<?>)result.get("items")).hasSize(1);
        String body=mapper.writeValueAsString(admins.detail(admin,a));
        assertThat(body).doesNotContain("password_hash","credential_version","token_digest",password);
        assertThat(admins.configuration(admin)).containsEntry("registrationEnabled",true).containsEntry("maxAccounts",3);
        error(()->admins.users(admin,"","ALL","ALL",-1,10),ErrorCode.VALIDATION_ERROR);
        error(()->admins.users(admin,"","ALL","ALL",0,1000),ErrorCode.VALIDATION_ERROR);
    }
    List<Boolean> race(Callable<Boolean> first,Callable<Boolean> second)throws Exception {
        var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)) {
            var f=pool.submit(()->{barrier.await();return first.call();});var s=pool.submit(()->{barrier.await();return second.call();});return List.of(f.get(30,TimeUnit.SECONDS),s.get(30,TimeUnit.SECONDS));
        }
    }
    @Test void createAndRegistrationShareLastGlobalSeatAndDoubleClickCreatesOnce()throws Exception {
        admins.create(admin,UUID.randomUUID(),name(),password,null);admins.create(admin,UUID.randomUUID(),name(),password,null);
        var invite=invites.generate(a);String first=name(),second=name();
        assertThat(race(()->{try{admins.create(admin,UUID.randomUUID(),first,password,null);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.REGISTRATION_LIMITED);return false;}},
            ()->{try{registrations.register(invite.token(),second,password,null);return true;}catch(ApiFailure e){assertThat(e.code()).isEqualTo(ErrorCode.REGISTRATION_LIMITED);return false;}})).containsExactlyInAnyOrder(true,false);
        assertThat(jdbc.queryForObject("select (select count(*) from registration_use)+(select count(*) from admin_account_allocation)",Long.class)).isEqualTo(3);
        jdbc.update("delete from registration_use");jdbc.update("delete from admin_account_allocation");
        UUID op=UUID.randomUUID();String third=name();
        Callable<Boolean> create=()->{admins.create(admin,op,third,password,null);return true;};assertThat(race(create,create)).containsExactly(true,true);
        assertThat(jdbc.queryForObject("select count(*) from app_user where username=?",Integer.class,third)).isOne();
    }
    @Test void failureRollsBackAccountReservationAndAuditNeverContainsPasswords() {
        jdbc.execute("create function fail_admin_allocation() returns trigger language plpgsql as $$ begin raise exception 'injected'; end $$");
        jdbc.execute("create trigger fail_admin_allocation before insert on admin_account_allocation for each row execute function fail_admin_allocation()");
        String failed=name();try {assertThatThrownBy(()->admins.create(admin,UUID.randomUUID(),failed,password,null)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("drop trigger fail_admin_allocation on admin_account_allocation");jdbc.execute("drop function fail_admin_allocation()");}
        assertThat(jdbc.queryForObject("select count(*) from app_user where username=?",Integer.class,failed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from account_name where normalized=?",Integer.class,failed)).isZero();
        admins.reset(admin,a,0,replacement);
        String audit=jdbc.queryForList("select before_value,after_value,result from account_admin_audit").toString();
        assertThat(audit).contains("FAILED","sessionsRevoked").doesNotContain(password,replacement,"password_hash");
    }
    @Test void personalBackupCannotRestoreRolesPermissionsStatusOrAudit()throws Exception {
        var backups=app.getBean(BackupService.class);UUID id=UUID.fromString(backups.createExport(a,UUID.randomUUID()).path("id").asText());backups.export(a,id);byte[] bytes=backups.download(a,id).getContentAsByteArray();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())assertThat(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("account_admin_audit","admin_create_receipt","invitation_permission","must_change_password","password_hash");}
        admins.permission(admin,a,0,false,1);admins.status(admin,a,1,false);
        UUID restore=UUID.fromString(backups.upload(a,UUID.randomUUID(),new MockMultipartFile("file","accounts.zip","application/zip",bytes)).path("id").asText());backups.validate(a,restore);var p=backups.job(a,restore,true).path("validation_result");backups.confirm(a,restore,UUID.fromString(p.path("expectedEpoch").asText()),p.path("expectedRevision").asLong(),true);backups.restore(a,restore);
        assertThat(jdbc.queryForObject("select enabled from app_user where id=?",Boolean.class,a)).isFalse();assertThat(jdbc.queryForObject("select role from app_user where id=?",String.class,a)).isEqualTo("USER");assertThat(invites.overview(a).allowed()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from account_admin_audit where target_id=? and result='SUCCESS'",Long.class,a)).isEqualTo(2);
    }
    int maintenance(String... args)throws Exception {
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java.exe").toString());command.add("-cp");command.add(System.getProperty("java.class.path"));command.add("com.interviewflashcards.tools.AccountAdmin");command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);builder.environment().put("DATABASE_SCHEMA",schema);var process=builder.start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);int result=process.waitFor();if(result!=0)assertThat(output).contains("Cannot remove last enabled administrator");return result;
    }
    @Test void maintenanceExplicitBindingPreservesCredentialsAndProtectsLastAdminConcurrently()throws Exception {
        String hash=jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a);UUID epoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,a);
        assertThat(maintenance("role",username(a),"ADMIN")).isZero();
        assertThat(jdbc.queryForObject("select password_hash from app_user where id=?",String.class,a)).isEqualTo(hash);assertThat(jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,a)).isEqualTo(epoch);
        var outcomes=race(()->maintenance("role",username(a),"USER")==0,()->maintenance("role",username(admin),"USER")==0);
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(jdbc.queryForObject("select count(*) from app_user where role='ADMIN' and enabled",Long.class)).isOne();
    }
}
