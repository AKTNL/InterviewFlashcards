package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.sync.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
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
class IncrementalSyncIT {
    @TempDir static Path directory;
    final String schema="delta_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;
    JdbcTemplate jdbc; ObjectMapper mapper; MockMvc mvc; SyncService sync;
    TransactionTemplate tx; UUID owner, other;
    @BeforeAll void start() {
        startApp();
        owner=jdbc.queryForObject("select id from app_user where username='delta'",UUID.class);
        other=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,'other','unused')",other);
    }
    void startApp() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
                "--server.port=0","--app.import.poll-ms=3600000","--spring.flyway.schemas="+schema,
                "--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
                "--app.account.username=delta","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("delta-test-only"),
                "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class); mapper=app.getBean(ObjectMapper.class);sync=app.getBean(SyncService.class);
        tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
    UUID epoch(UUID user) {return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user);}
    long revision(UUID user) {return jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);}
    void write(UUID user,Runnable work) {
        tx.executeWithoutResult(status->{jdbc.queryForMap("select id from app_user where id=? for no key update",user);work.run();jdbc.update("update app_user set revision=revision+1 where id=?",user);});
    }
    JsonNode changes(UUID user,UUID epoch,long since) throws Exception {return mapper.valueToTree(sync.changes(user,epoch,since));}
    JsonNode changes(long since) throws Exception {return changes(owner,epoch(owner),since);}
    MockHttpSession login() throws Exception {
        var csrf=mvc.perform(get("/api/auth/csrf")).andReturn();var session=(MockHttpSession)csrf.getRequest().getSession();
        var token=mapper.readTree(csrf.getResponse().getContentAsByteArray()).at("/data/token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","delta").param("password","delta-test-only")).andExpect(status().isOk());
        return session;
    }
    @Test void disconnectedUpdatesAndDeletesMatchAuthoritativeRowsAndExcludeSecrets() throws Exception {
        long start=revision(owner);UUID id=UUID.randomUUID(),source=UUID.randomUUID();
        write(owner,()->{
            jdbc.update("insert into deck(id,user_id,name) values(?,?,'initial')",id,owner);
            jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'original.pdf',?,1,10,?)",source,owner,"1".repeat(64),UUID.randomUUID());
        });
        write(owner,()->{jdbc.update("update deck set name='new',version=version+1 where id=?",id);jdbc.update("update source_document set original_filename='renamed.pdf',version=version+1 where id=?",source);});
        var delta=changes(start);
        assertThat(delta.path("fullRequired").asBoolean()).isFalse();assertThat(delta.path("revision").asLong()).isEqualTo(start+2);
        var rows=new ArrayList<JsonNode>();delta.path("changes").forEach(rows::add);
        assertThat(rows).anySatisfy(r->{assertThat(r.path("table").asText()).isEqualTo("deck");assertThat(r.at("/row/name").asText()).isEqualTo("new");});
        assertThat(rows).anySatisfy(r->{assertThat(r.path("table").asText()).isEqualTo("source_document");assertThat(r.at("/row/originalFilename").asText()).isEqualTo("renamed.pdf");});
        assertThat(delta.toString()).doesNotContain("storage_key","user_id","raw_evidence","attempt_token");
        write(owner,()->jdbc.update("delete from deck where id=?",id));
        var deleted=changes(start+2).path("changes");assertThat(deleted.size()).isEqualTo(1);
        assertThat(deleted.get(0).path("key").path("id").asText()).isEqualTo(id.toString());assertThat(deleted.get(0).path("row").isNull()).isTrue();
        assertThat(changes(revision(owner)).path("changes").size()).isZero();
    }
    @Test void rollbackAndIdempotentReceiptCannotCreatePhantomChanges() throws Exception {
        long start=revision(owner);UUID id=UUID.randomUUID();
        assertThatThrownBy(()->write(owner,()->{jdbc.update("insert into deck(id,user_id,name) values(?,?,'rolled back')",id,owner);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(revision(owner)).isEqualTo(start);assertThat(changes(start).path("changes").size()).isZero();
        var mutation=app.getBean(MutationService.class);var command=new MutationService.Command(UUID.randomUUID(),epoch(owner));
        mutation.execute(owner,command,"delta.test",Map.of(),()->{jdbc.update("insert into deck(id,user_id,name) values(?,?,'once')",id,owner);return new MutationService.Change(Map.of(),0,true);});
        mutation.execute(owner,command,"delta.test",Map.of(),()->{throw new AssertionError("replayed work");});
        assertThat(revision(owner)).isEqualTo(start+1);assertThat(changes(start).path("changes").size()).isEqualTo(1);
    }
    @Test void compositeAndNumericKeysSurviveDeletionAndReinsertion() throws Exception {
        UUID deck=UUID.randomUUID(),module=UUID.randomUUID(),chapter=UUID.randomUUID(),preference=UUID.randomUUID();long start=revision(owner);
        write(owner,()->{
            jdbc.update("insert into deck(id,user_id,name) values(?,?,'composite')",deck,owner);
            jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'m','m')",module,owner,deck);
            jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'c','c')",chapter,owner,module);
            jdbc.update("insert into learning_preference(id,user_id) values(?,?)",preference,owner);
            jdbc.update("insert into learning_preference_chapter(user_id,preference_id,chapter_id) values(?,?,?)",owner,preference,chapter);
            jdbc.update("insert into review_policy(user_id,version,algorithm_version,intervals,reason,created_at) values(?,1,'test-policy','[1,3,7,14,30]','test',current_timestamp)",owner);
        });
        var initial=changes(start);assertThat(initial.toString()).contains("preference_id","chapter_id","test-policy");
        long before=revision(owner);
        write(owner,()->{jdbc.update("delete from learning_preference_chapter where user_id=?",owner);jdbc.update("delete from review_policy where user_id=?",owner);});
        var removed=changes(before).path("changes");assertThat(removed.size()).isEqualTo(2);
        removed.forEach(row->assertThat(row.path("row").isNull()).isTrue());
        assertThat(removed.toString()).contains("\"version\":1");
        write(owner,()->jdbc.update("insert into learning_preference_chapter(user_id,preference_id,chapter_id) values(?,?,?)",owner,preference,chapter));
        assertThat(changes(before).path("changes").toString()).contains(preference.toString());
    }
    @Test void epochFutureExpiredAndLargeCursorsRequireFullRefresh() throws Exception {
        assertThat(changes(owner,UUID.randomUUID(),revision(owner)).path("reason").asText()).isEqualTo("EPOCH_CHANGED");
        assertThat(changes(revision(owner)+1).path("reason").asText()).isEqualTo("CURSOR_AHEAD");
        long start=revision(other);write(other,()->{});
        jdbc.update("delete from sync_revision where user_id=?",other);
        assertThat(changes(other,epoch(other),start).path("reason").asText()).isEqualTo("CURSOR_EXPIRED");
        jdbc.update("update app_user set revision=revision+1001 where id=?",other);
        assertThat(changes(other,epoch(other),start).path("reason").asText()).isEqualTo("TOO_MANY_REVISIONS");
        long before=revision(owner);var oldEpoch=epoch(owner);
        tx.executeWithoutResult(status->jdbc.update("update app_user set data_epoch=?,revision=revision+1 where id=?",UUID.randomUUID(),owner));
        assertThat(changes(owner,oldEpoch,before).path("fullRequired").asBoolean()).isTrue();
    }
    @Test void boundedRowPayloadFallsBackWithoutAdvancingAnIncompleteCursor() throws Exception {
        UUID user=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,'large','unused')",user);
        write(user,()->jdbc.update("insert into tag(id,user_id,name) select gen_random_uuid(),?, 'tag-'||n from generate_series(1,5001)n",user));
        var delta=changes(user,epoch(user),0);assertThat(delta.path("reason").asText()).isEqualTo("TOO_MANY_ROWS");assertThat(delta.has("changes")).isFalse();
    }
    @Test void endpointsAreAuthenticatedScopedAndRejectInvalidCursors() throws Exception {
        for(String path:List.of("/api/sync/changes?dataEpoch="+epoch(owner)+"&since=0","/api/sync/notifications?dataEpoch="+epoch(owner)+"&since=0&learningDate=2026-11-01"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var session=login();
        mvc.perform(get("/api/sync/changes").session(session).param("dataEpoch",epoch(owner).toString()).param("since","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sync/changes").session(session).header("X-Account-ID",other.toString()).param("dataEpoch",epoch(other).toString()).param("since","0")).andExpect(status().isConflict());
        var result=mvc.perform(get("/api/sync/changes").session(session).param("dataEpoch",epoch(other).toString()).param("since","0")).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/reason").asText()).isEqualTo("EPOCH_CHANGED");
    }
    @Test void journalSurvivesServerRestartAndExpiredHistoryRequiresAFullRefresh() throws Exception {
        long before=revision(owner);UUID id=UUID.randomUUID();var epoch=epoch(owner);
        write(owner,()->jdbc.update("insert into deck(id,user_id,name) values(?,?,'survives restart')",id,owner));
        app.close();startApp();
        assertThat(changes(owner,epoch,before).path("changes").toString()).contains(id.toString(),"survives restart");
        UUID stale=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,'retention','unused')",stale);
        write(stale,()->jdbc.update("insert into tag(id,user_id,name) values(?,?,'expired')",UUID.randomUUID(),stale));
        jdbc.update("update sync_revision set created_at=current_timestamp-interval '8 days' where user_id=?",stale);
        jdbc.update("update sync_row_change set created_at=current_timestamp-interval '8 days' where user_id=?",stale);
        app.getBean(SyncNotifications.class).prune();
        assertThat(changes(stale,epoch(stale),0).path("reason").asText()).isEqualTo("CURSOR_EXPIRED");
        assertThat(changes(stale,epoch(stale),1).path("changes").size()).isZero();
    }
    @Test void notificationsWaitForCommittedChangesAndRejectRevokedSessions() throws Exception {
        var session=login();long start=revision(owner);var epoch=epoch(owner);
        var request=mvc.perform(get("/api/sync/notifications").session(session).param("dataEpoch",epoch.toString()).param("since",Long.toString(start))
                .param("learningDate",app.getBean(com.interviewflashcards.common.time.LearningTime.class).today().toString())).andExpect(request().asyncStarted()).andReturn();
        write(other,()->{});app.getBean(SyncNotifications.class).poll();
        assertThatThrownBy(()->request.getAsyncResult(50)).isInstanceOf(IllegalStateException.class);
        write(owner,()->{});app.getBean(SyncNotifications.class).poll();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(start+1));
        var revoked=mvc.perform(get("/api/sync/notifications").session(session).param("dataEpoch",epoch.toString()).param("since",Long.toString(start+1))
                .param("learningDate",app.getBean(com.interviewflashcards.common.time.LearningTime.class).today().toString())).andExpect(request().asyncStarted()).andReturn();
        jdbc.update("update app_user set credential_version=credential_version+1 where id=?",owner);app.getBean(SyncNotifications.class).poll();
        mvc.perform(asyncDispatch(revoked)).andExpect(status().isUnauthorized());
    }
}
