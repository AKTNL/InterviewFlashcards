package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.learning.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LearningIT {
    @TempDir static Path directory;
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-10-31T04:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @TestConfiguration static class TimeOverride { @Bean @Primary TestClock learningTestClock(){return new TestClock();} }
    private final String schema="learning_it_"+UUID.randomUUID().toString().replace("-","");
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc; private ObjectMapper mapper; private LearningService learning; private MockMvc mvc; private TestClock clock;
    private UUID user,epoch,chapter; private List<UUID> cards;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class,TimeOverride.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,
            "--spring.datasource.hikari.schema="+schema,"--app.account.username=learning-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);learning=app.getBean(LearningService.class);clock=app.getBean(TestClock.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    @BeforeEach void seed() {
        clock.value=Instant.parse("2026-10-31T04:00:00Z");user=UUID.randomUUID();epoch=UUID.randomUUID();cards=new ArrayList<>();
        UUID source=UUID.randomUUID(),job=UUID.randomUUID(),deck=UUID.randomUUID(),module=UUID.randomUUID(),chapter=UUID.randomUUID();
        this.chapter=chapter;
        jdbc.update("insert into app_user(id,username,password_hash,data_epoch) values(?,?,'unused',?)",user,"u-"+user,epoch);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'test.pdf',?,1,10,?)",source,user,"b".repeat(64),UUID.randomUUID());
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",deck,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,user,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'Java','Java','IMPORTED')",chapter,user,module);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id) values(?,?,?,'READY',?)",job,user,source,deck);
        for(int i=0;i<3;i++){
            UUID draft=UUID.randomUUID(),card=UUID.randomUUID();cards.add(card);
            jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,sort_order) values(?,?,?,?,?,?,?)",draft,user,chapter,job,"q"+i,"问题"+i,i);
            jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,?)",card,user,chapter,draft,"问题"+i);
        }
    }
    private JsonNode session(int... indexes) throws Exception {
        var ids=Arrays.stream(indexes).mapToObj(cards::get).toList();
        return learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,ids)).get("session");
    }
    private LearningController.Write command(JsonNode s,String action,String rating) {
        var p=mapper.createObjectNode().put("itemId",s.at("/current/id").asText()).put("expectedReviewVersion",s.at("/current/expectedVersion").asLong())
            .put("answerRevealed",true).put("confirmEarly",true).put("confirmed",true).put("rating",rating==null?"":rating)
            .put("eventId",s.at("/events/0/id").asText());
        return new LearningController.Write(UUID.randomUUID(),epoch,s.get("version").asLong(),action,p);
    }
    private JsonNode write(JsonNode s,String action,String rating) throws Exception {
        return learning.write(user,UUID.fromString(s.get("id").asText()),command(s,action,rating));
    }
    private JsonNode current(JsonNode s){return mapper.valueToTree(learning.session(user,UUID.fromString(s.get("id").asText())));}
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,user);}
    private LearningController.Preferences preferenceCommand(int target,int batch,List<UUID> chapters) {
        return new LearningController.Preferences(UUID.randomUUID(),epoch,((Number)learning.preferences(user).get("version")).longValue(),target,batch,null,chapters);
    }
    private void prefer(int target,int batch,List<UUID> chapters) throws Exception { learning.savePreferences(user,preferenceCommand(target,batch,chapters)); }
    private JsonNode daily(String mode) throws Exception { return learning.daily(user,new LearningController.Plan(UUID.randomUUID(),epoch,mode)).get("session"); }
    private ReviewPolicyService policies() { return app.getBean(ReviewPolicyService.class); }
    private LearningController.ReviewParameters parameters(long version,List<Integer> intervals) {
        return new LearningController.ReviewParameters(UUID.randomUUID(),epoch,version,intervals,"缩短间隔观察后续自评");
    }
    @Test void incrementalScoringParametersReplayAndUndoMatchFullSnapshots() throws Exception {
        var sync=app.getBean(com.interviewflashcards.sync.SyncService.class);
        JsonNode before=mapper.valueToTree(sync.snapshot(user,true));
        var s=session(0);
        before=assertDeltaMatches(sync,before);
        policies().save(user,parameters(0,List.of(2,4,8,16,32)));
        before=assertDeltaMatches(sync,before);
        var result=write(s,"RATE","FORGOT");
        before=assertDeltaMatches(sync,before);
        write(result.path("session"),"UNDO",null);
        assertDeltaMatches(sync,before);
    }
    private JsonNode assertDeltaMatches(com.interviewflashcards.sync.SyncService sync,JsonNode before) throws Exception {
        JsonNode delta=mapper.valueToTree(sync.changes(user,epoch,before.path("revision").asLong()));
        assertThat(delta.path("fullRequired").asBoolean()).isFalse();
        var rebuilt=(com.fasterxml.jackson.databind.node.ObjectNode)before.deepCopy();
        for(var change:delta.path("changes")) {
            String table=change.path("table").asText();
            var rows=(com.fasterxml.jackson.databind.node.ArrayNode)(table.equals("source_document")?rebuilt.path("sources"):rebuilt.path("data").path(table));
            for(int i=rows.size()-1;i>=0;i--) {
                JsonNode candidate=rows.get(i);
                if(change.path("key").properties().stream().allMatch(e->e.getValue().equals(candidate.path(e.getKey())))) rows.remove(i);
            }
            if(!change.path("row").isNull())rows.add(change.path("row"));
        }
        JsonNode after=mapper.valueToTree(sync.snapshot(user,true));
        assertThat(delta.path("revision")).isEqualTo(after.path("revision"));
        after.path("data").properties().forEach(e->{
            var actual=new HashSet<JsonNode>();rebuilt.path("data").path(e.getKey()).forEach(actual::add);
            var expected=new HashSet<JsonNode>();e.getValue().forEach(expected::add);
            assertThat(actual).as("incremental %s",e.getKey()).isEqualTo(expected);
        });
        assertThat(rebuilt.path("sources")).isEqualTo(after.path("sources"));
        return after;
    }
    @Test void personalParametersAreImmutableVersionedIdempotentAndDoNotRescheduleExistingCards() throws Exception {
        var s=write(session(0),"RATE","REMEMBERED").path("session");
        var due=jdbc.queryForObject("select due_date from review_state where user_id=?",LocalDate.class,user);
        var c=parameters(0,List.of(2,4,8,16,32));var saved=policies().save(user,c);
        assertThat(policies().save(user,c)).isEqualTo(mapper.readTree(saved.toString()));
        assertThat(count("review_policy")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select due_date from review_state where user_id=?",LocalDate.class,user)).isEqualTo(due);
        assertThatThrownBy(()->policies().save(user,parameters(0,List.of(1,2,4,8,16)))).isInstanceOf(ApiFailure.class);
        var result=write(session(1),"RATE","REMEMBERED");
        assertThat(result.path("dueDate").asText()).isEqualTo("2026-11-02");
        assertThat(jdbc.queryForObject("select algorithm_version from review_event where id=?",String.class,UUID.fromString(result.path("eventId").asText())))
            .isEqualTo(saved.at("/current/algorithmVersion").asText());
        assertThat(jdbc.queryForObject("select algorithm_parameters->'intervals' from review_event where id=?",String.class,UUID.fromString(result.path("eventId").asText())))
            .isEqualTo("[2, 4, 8, 16, 32]");
        write(result.path("session"),"UNDO",null);
        assertThat(jdbc.queryForObject("select algorithm_parameters->'intervals' from review_event where user_id=? and kind='UNDO'",String.class,user)).isEqualTo("[2, 4, 8, 16, 32]");
        assertThat(policies().active(UUID.randomUUID()).version()).isZero();
        assertThat(policies().active(user).version()).isEqualTo(1);
        assertThat(s.path("id").isTextual()).isTrue();
    }
    @Test void parametersValidateBoundsOrderReasonEpochSecurityAndReceiptRollback() throws Exception {
        for(var values:List.of(List.of(0,2,3,4,5),List.of(1,3,2,4,5),List.of(1,2,3,4,366),List.of(1,2)))
            assertThatThrownBy(()->policies().save(user,parameters(0,values))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->policies().save(user,new LearningController.ReviewParameters(UUID.randomUUID(),epoch,0L,List.of(1,2,3,4,5)," "))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->policies().save(user,new LearningController.ReviewParameters(UUID.randomUUID(),UUID.randomUUID(),0L,List.of(1,2,3,4,5),"test"))).isInstanceOf(ApiFailure.class);
        var owner=user(new AccountPrincipal(user,"test",""));var c=parameters(0,List.of(1,2,3,4,5));
        mvc.perform(get("/api/review-parameters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/review-parameters?days=31").with(owner)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/review-parameters").with(owner).contentType("application/json").content(mapper.writeValueAsString(c))).andExpect(status().isForbidden());
        mvc.perform(post("/api/review-parameters").with(owner).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(parameters(0,List.of(1,2))))).andExpect(status().isBadRequest());
        jdbc.execute("create function fail_policy_receipt() returns trigger language plpgsql as $$ begin raise exception 'failure'; end $$");
        jdbc.execute("create trigger fail_policy_receipt before insert on mutation_receipt for each row execute function fail_policy_receipt()");
        try { assertThatThrownBy(()->policies().save(user,c)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger fail_policy_receipt on mutation_receipt");jdbc.execute("drop function fail_policy_receipt()"); }
        assertThat(count("review_policy")).isZero();
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isZero();
        mvc.perform(post("/api/review-parameters").with(owner).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(c))).andExpect(status().isOk());
    }
    @Test void outcomesUseSchedulingPolicyAndExcludeFirstReplayEarlyUndoAndReset() throws Exception {
        var original=write(session(0),"RATE","REMEMBERED");
        write(session(1),"RATE","REMEMBERED");
        write(session(2),"RATE","REMEMBERED");
        var next=policies().save(user,parameters(0,List.of(2,4,8,16,32)));
        write(session(0),"RATE","REMEMBERED"); // A replay that retains the due date cannot take credit for the old schedule.
        assertThat(mapper.valueToTree(policies().report(user,90)).path("outcomes").size()).isZero();
        clock.value=clock.value.plus(Duration.ofDays(1));
        var result=write(session(0),"RATE","VAGUE");
        var report=mapper.valueToTree(policies().report(user,90));var row=report.at("/outcomes/0");
        assertThat(row.path("algorithmVersion").asText()).isEqualTo("rules-v1");
        assertThat(row.path("observations").asInt()).isEqualTo(1);assertThat(row.path("vague").asInt()).isEqualTo(1);
        assertThat(row.path("onTime").asInt()).isEqualTo(1);
        write(result.path("session"),"UNDO",null);
        assertThat(mapper.valueToTree(policies().report(user,90)).path("outcomes").size()).isZero();
        write(session(0),"RATE","REMEMBERED");
        // Explicit reset interrupts the observation chain.
        var reset=write(session(1),"RELEARN",null).path("session");write(reset,"RATE","REMEMBERED");
        clock.value=clock.value.plus(Duration.ofDays(1)); // card 2 is now one day late.
        write(session(2),"RATE","FORGOT");
        report=mapper.valueToTree(policies().report(user,90));row=report.at("/outcomes/0");
        assertThat(row.path("observations").asInt()).isEqualTo(2);assertThat(row.path("late").asInt()).isEqualTo(1);
        assertThat(row.path("forgot").asInt()).isEqualTo(1);
        assertThat(report.path("outcomes").toString()).doesNotContain(next.at("/current/algorithmVersion").asText());
        clock.value=clock.value.plus(Duration.ofDays(10));write(session(0),"RATE","REMEMBERED");
        report=mapper.valueToTree(policies().report(user,90));assertThat(report.path("outcomes").size()).isEqualTo(2);
        assertThat(mapper.valueToTree(policies().report(UUID.randomUUID(),90)).path("outcomes").size()).isZero();
        assertThat(original.path("eventId").isTextual()).isTrue();
    }
    @Test void preferencesAreExplicitVersionedIdempotentAndReadsDoNotWrite() throws Exception {
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        assertThat(learning.preferences(user)).containsEntry("saved",false).containsEntry("dailyNewCount",5).containsEntry("reviewBatchSize",20).containsEntry("referenceMinutes",null);
        learning.today(user);assertThat(count("learning_preference")).isZero();
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
        assertThatThrownBy(()->daily("MIXED")).isInstanceOf(ApiFailure.class);
        var c=preferenceCommand(0,1,List.of(chapter));var result=learning.savePreferences(user,c);
        assertThat(learning.savePreferences(user,c)).isEqualTo(mapper.readTree(result.toString()));
        assertThat(result.at("/preferences/version").asLong()).isEqualTo(1);
        assertThatThrownBy(()->learning.savePreferences(user,new LearningController.Preferences(UUID.randomUUID(),epoch,0L,5,20,null,List.of(chapter)))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->prefer(5,20,List.of(UUID.randomUUID()))).isInstanceOf(ApiFailure.class);
        assertThat(learning.preferences(user)).containsEntry("dailyNewCount",0).containsEntry("version",1L);
    }
    @Test void zeroNewGoalAndBatchNeverHideTotalDueAndOrderIsDeterministic() throws Exception {
        clock.value=clock.value.minus(Duration.ofDays(3));
        for(int i=0;i<3;i++) write(session(i),"RATE",i==1?"VAGUE":"REMEMBERED");
        clock.value=clock.value.plus(Duration.ofDays(3));
        jdbc.update("update review_state set due_date=? where user_id=?",LocalDate.of(2026,10,30),user);
        jdbc.update("update review_state set due_date=? where card_id=?",LocalDate.of(2026,10,29),cards.get(2));
        prefer(0,2,List.of(chapter));
        var t=learning.today(user);assertThat(t).containsEntry("dueTotal",3L).containsEntry("remainingNew",0);
        var s=daily("MIXED");assertThat(s.get("items").size()).isEqualTo(2);
        assertThat(s.at("/items/0/cardId").asText()).isEqualTo(cards.get(2).toString());
        assertThat(s.at("/items/1/cardId").asText()).isEqualTo(cards.get(1).toString());
        s=write(s,"RATE","REMEMBERED").get("session");s=write(s,"RATE","REMEMBERED").get("session");
        assertThat(s.get("current").isNull()).isTrue();assertThat(s.at("/round/reviewCount").asLong()).isEqualTo(2);
        assertThat(learning.today(user)).containsEntry("dueTotal",1L);
        assertThat(daily("NEW").get("current").isNull()).isTrue();
        assertThat(daily("REVIEW").at("/current/cardId").asText()).isEqualTo(cards.getFirst().toString());
    }
    @Test void newQuotaCountsOnlyEffectiveScoresAndTargetChangesPreserveHistory() throws Exception {
        prefer(2,20,List.of(chapter));var s=daily("NEW");
        assertThat(s.at("/items/0/cardId").asText()).isEqualTo(cards.getFirst().toString());
        assertThat(s.at("/items/1/cardId").asText()).isEqualTo(cards.get(1).toString());
        assertThat(learning.today(user)).containsEntry("remainingNew",2);
        assertThat(daily("NEW").get("id")).isEqualTo(s.get("id"));
        s=write(s,"RATE","REMEMBERED").get("session");var after=s.get("events").get(0).get("afterState");
        assertThat(learning.today(user)).containsEntry("remainingNew",1);
        prefer(0,20,List.of(chapter));
        assertThat(current(s).get("current").isNull()).isTrue();
        assertThat(learning.summary(user)).containsEntry("todayNew",1L);
        assertThat(jdbc.queryForObject("select due_date from review_state where card_id=?",LocalDate.class,cards.getFirst())).isEqualTo(LocalDate.parse(after.get("dueDate").asText()));
        prefer(3,20,List.of(chapter));var restored=current(s);
        assertThat(restored.at("/current/cardId").asText()).isEqualTo(cards.get(1).toString());
        assertThat(learning.today(user)).containsEntry("remainingNew",2);
    }
    @Test void pausingSkipsPendingQueueAndResumePreservesOriginalOverdueDates() throws Exception {
        clock.value=clock.value.minus(Duration.ofDays(5));write(session(0),"RATE","VAGUE");clock.value=clock.value.plus(Duration.ofDays(5));
        prefer(1,20,List.of(chapter));var s=daily("MIXED");var due=jdbc.queryForObject("select due_date from review_state where card_id=?",LocalDate.class,cards.getFirst());
        prefer(1,20,List.of());
        assertThat(current(s).get("current").isNull()).isTrue();assertThat(learning.today(user)).containsEntry("dueTotal",0L);
        assertThatThrownBy(()->write(s,"RATE","REMEMBERED")).isInstanceOf(ApiFailure.class);
        assertThat(jdbc.queryForObject("select due_date from review_state where card_id=?",LocalDate.class,cards.getFirst())).isEqualTo(due);
        prefer(1,20,List.of(chapter));assertThat(learning.today(user)).containsEntry("dueTotal",1L);
        assertThat(daily("MIXED").at("/current/cardId").asText()).isEqualTo(cards.getFirst().toString());
    }
    @Test void anotherSessionProcessingAnItemIsSkippedWithoutDuplicateNewLearning() throws Exception {
        prefer(2,20,List.of(chapter));var s=daily("NEW");
        write(session(0),"RATE","REMEMBERED");var resumed=current(s);
        assertThat(resumed.at("/items/0/unavailable").asBoolean()).isTrue();
        assertThat(resumed.at("/current/cardId").asText()).isEqualTo(cards.get(1).toString());
        assertThat(write(s,"RATE","REMEMBERED").path("duplicateScore").asBoolean()).isTrue();
        write(resumed,"RATE","REMEMBERED");assertThat(learning.summary(user)).containsEntry("todayNew",2L);
        assertThat(learning.today(user)).containsEntry("remainingNew",0);
    }
    @Test void midnightRecalculatesQuotaAndDueWithoutReusingYesterdaySession() throws Exception {
        clock.value=Instant.parse("2026-10-31T15:59:59Z");prefer(1,20,List.of(chapter));var s=daily("NEW");
        s=write(s,"RATE","REMEMBERED").get("session");assertThat(learning.today(user)).containsEntry("remainingNew",0);
        clock.value=clock.value.plusSeconds(2);
        assertThat(current(s).get("expired").asBoolean()).isTrue();
        var yesterday=s;assertThatThrownBy(()->write(current(yesterday),"UNDO",null)).isInstanceOf(ApiFailure.class);
        assertThat(learning.today(user)).containsEntry("remainingNew",1).containsEntry("dueTotal",1L);
        var next=daily("MIXED");assertThat(next.get("id")).isNotEqualTo(s.get("id"));
        assertThat(next.at("/items/0/itemKind").asText()).isEqualTo("REVIEW");assertThat(next.at("/items/1/itemKind").asText()).isEqualTo("NEW");
        assertThat(next.get("learningDate").asText()).isEqualTo("2026-11-01");
    }
    @Test void roundStatsAttentionAndUndoUseEffectiveEvents() throws Exception {
        prefer(1,20,List.of(chapter));var s=write(daily("NEW"),"RATE","VAGUE").get("session");
        assertThat(s.at("/round/newCount").asInt()).isEqualTo(1);assertThat(s.at("/round/attention/0/id").asText()).isEqualTo(cards.getFirst().toString());
        assertThat(learning.today(user).get("chapters").toString()).contains("weak=1");
        s=write(s,"UNDO",null).get("session");assertThat(s.at("/round/newCount").asInt()).isZero();assertThat(s.at("/round/attention").size()).isZero();
        assertThat(s.at("/current/cardId").asText()).isEqualTo(cards.getFirst().toString());assertThat(learning.today(user)).containsEntry("remainingNew",1);
    }
    @Test void dailyRoutesEnforceSecurityAndPreferenceFailureRollsBack() throws Exception {
        var owner=user(new AccountPrincipal(user,"test",""));
        mvc.perform(get("/api/today")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/preferences").with(owner)).andExpect(status().isOk());
        var c=preferenceCommand(0,20,List.of(chapter));String body=mapper.writeValueAsString(c);
        mvc.perform(post("/api/preferences").with(owner).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/preferences").with(owner).with(csrf()).contentType("application/json").content(body.replace("\"reviewBatchSize\":20","\"reviewBatchSize\":0"))).andExpect(status().isBadRequest());
        jdbc.execute("create function fail_daily_receipt() returns trigger language plpgsql as $$ begin raise exception 'receipt failure'; end $$");
        jdbc.execute("create trigger fail_daily_receipt before insert on mutation_receipt for each row execute function fail_daily_receipt()");
        try { assertThatThrownBy(()->learning.savePreferences(user,c)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger fail_daily_receipt on mutation_receipt");jdbc.execute("drop function fail_daily_receipt()"); }
        assertThat(count("learning_preference")).isZero();assertThat(count("learning_preference_chapter")).isZero();
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isZero();
        prefer(5,20,List.of(chapter));
        mvc.perform(post("/api/learning-sessions/daily").with(owner).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(new LearningController.Plan(UUID.randomUUID(),epoch,"NEW")))).andExpect(status().isOk());
    }
    @Test void scoreIsIdempotentAndStateEventReceiptAndRevisionCommitTogether() throws Exception {
        var s=session(0,1);var c=command(s,"RATE","FORGOT");UUID id=UUID.fromString(s.get("id").asText());
        var first=learning.write(user,id,c);assertThat(learning.write(user,id,c)).isEqualTo(mapper.readTree(first.toString()));
        assertThat(first.at("/session/canUndo").asBoolean()).isTrue();
        assertThat(count("review_event")).isEqualTo(1);assertThat(count("review_state")).isEqualTo(1);assertThat(count("session_item")).isEqualTo(3);
        assertThat(first.at("/session/current/cardId").asText()).isEqualTo(cards.get(1).toString());assertThat(first.get("dueDate").asText()).isEqualTo("2026-11-01");
        assertThat(learning.summary(user)).containsEntry("todayNew",1L).containsEntry("todayReview",0L);
        assertThatThrownBy(()->learning.write(user,id,new LearningController.Write(c.operationId(),epoch,0L,"SKIP",c.payload()))).isInstanceOf(ApiFailure.class);
    }
    @Test void replayLimitSurvivesNewSessionsAndSkipPreservesWeakState() throws Exception {
        var s=write(session(0),"RATE","FORGOT").get("session");
        assertThat(s.at("/current/isReplay").asBoolean()).isTrue();
        var replay=write(s,"RATE","REMEMBERED");assertThat(replay.get("isReplay").asBoolean()).isTrue();
        assertThat(replay.at("/session/events/0/afterState/stage").asInt()).isEqualTo(-1);
        assertThat(replay.at("/session/events/0/afterState/lapseCount").asInt()).isEqualTo(1);
        var again=write(session(0),"RATE","FORGOT").get("session");
        assertThat(again.get("current").isNull()).isTrue();assertThat(count("review_replay_allowance")).isEqualTo(1);
        assertThat(learning.summary(user)).containsEntry("todayNew",1L).containsEntry("todayReview",0L);
        var second=write(session(1),"RATE","FORGOT").get("session");write(second,"SKIP",null);
        assertThat(jdbc.queryForObject("select latest_rating from review_state where card_id=?",String.class,cards.get(1))).isEqualTo("FORGOT");
    }
    @Test void undoFirstScoreRestoresUnlearnedQueueAndStatsWithMonotonicVersion() throws Exception {
        var initial=session(0,1);var scored=write(initial,"RATE","FORGOT").get("session");
        var undone=write(scored,"UNDO",null).get("session");
        assertThat(undone.get("canUndo").asBoolean()).isFalse();
        assertThat(count("review_state")).isZero();assertThat(count("review_event")).isEqualTo(2);assertThat(count("session_item")).isEqualTo(2);
        assertThat(count("review_replay_allowance")).isZero();assertThat(undone.at("/current/cardId").asText()).isEqualTo(cards.get(0).toString());
        assertThat(undone.at("/current/expectedVersion").asLong()).isEqualTo(2);assertThat(learning.summary(user)).containsEntry("todayNew",0L).containsEntry("learned",0L);
        assertThatThrownBy(()->write(initial,"RATE","REMEMBERED")).isInstanceOf(ApiFailure.class);
        var rescore=write(undone,"RATE","REMEMBERED");assertThat(rescore.get("isReplay").asBoolean()).isFalse();
    }
    @Test void undoReplayRestoresOriginalRatingDueDateAndReplayPosition() throws Exception {
        var s=write(session(0),"RATE","FORGOT").get("session");var replay=write(s,"RATE","REMEMBERED").get("session");
        var undone=write(replay,"UNDO",null).get("session");assertThat(undone.at("/review/latestRating").asText()).isEqualTo("FORGOT");
        assertThat(undone.at("/review/dueDate").asText()).isEqualTo("2026-11-01");assertThat(undone.at("/current/isReplay").asBoolean()).isTrue();
        assertThat(learning.summary(user)).containsEntry("todayNew",1L);
    }
    @Test void undoLaterDayScoreRestoresPreviousScheduleAndReviewStatistics() throws Exception {
        write(session(0),"RATE","REMEMBERED");clock.value=clock.value.plus(Duration.ofDays(2));
        var before=session(0);var scored=write(before,"RATE","REMEMBERED").get("session");
        assertThat(learning.summary(user)).containsEntry("todayReview",1L);
        var undone=write(scored,"UNDO",null).get("session");
        assertThat(undone.at("/review/dueDate").asText()).isEqualTo(before.at("/review/dueDate").asText());
        assertThat(undone.at("/review/stage").asInt()).isEqualTo(before.at("/review/stage").asInt());
        assertThat(learning.summary(user)).containsEntry("todayReview",0L).containsEntry("learned",1L);
    }
    @Test void subsequentOtherDeviceWritePreventsUndoAndOldSessionScore() throws Exception {
        var a=session(0);var b=session(0);var scored=write(a,"RATE","REMEMBERED").get("session");
        assertThat(write(b,"RATE","FORGOT").at("/conflict/resolved").asBoolean()).isFalse();assertThat(count("review_event")).isEqualTo(1);
        var next=write(session(0),"RATE","VAGUE").get("session");
        assertThatThrownBy(()->write(scored,"UNDO",null)).isInstanceOf(ApiFailure.class);
        assertThat(current(next).at("/events/0/rating").asText()).isEqualTo("VAGUE");
    }
    @Test void relearnRequiresConfirmationPreservesHistoryAndDoesNotCountNewAgain() throws Exception {
        write(session(0),"RATE","REMEMBERED");clock.value=clock.value.plus(Duration.ofDays(2));
        var s=session(0);var c=command(s,"RELEARN",null);((com.fasterxml.jackson.databind.node.ObjectNode)c.payload()).put("confirmed",false);
        assertThatThrownBy(()->learning.write(user,UUID.fromString(s.get("id").asText()),c)).isInstanceOf(ApiFailure.class);
        var reset=write(s,"RELEARN",null).get("session");assertThat(reset.at("/review/stage").asInt()).isEqualTo(-1);
        assertThat(reset.at("/review/dueDate").asText()).isEqualTo("2026-11-02");assertThat(reset.at("/review/firstLearnedAt").asText()).isEqualTo("2026-10-31T04:00:00Z");
        write(reset,"RATE","REMEMBERED");assertThat(learning.summary(user)).containsEntry("todayNew",0L).containsEntry("todayReview",1L);
    }
    @Test void fractionalTimestampInEventMatchesPersistedStateAcrossRelearn() throws Exception {
        clock.value=Instant.parse("2026-10-31T04:00:00.123456789Z");
        var score=write(session(0),"RATE","REMEMBERED");
        var first=score.at("/session/events/0/afterState/firstLearnedAt").asText();
        var next=session(0);assertThat(next.at("/review/firstLearnedAt").asText()).isEqualTo(first);
        var reset=write(next,"RELEARN",null).get("session");assertThat(reset.at("/review/firstLearnedAt").asText()).isEqualTo(first);
        assertThat(first).isEqualTo("2026-10-31T04:00:00.123456Z");
    }
    @Test void clockRolloverRejectsOldSessionAndNextDayUsesNormalRules() throws Exception {
        var old=session(0);write(old,"RATE","FORGOT");clock.value=Instant.parse("2026-10-31T16:00:00Z");
        assertThat(current(old).get("expired").asBoolean()).isTrue();assertThatThrownBy(()->write(current(old),"UNDO",null)).isInstanceOf(ApiFailure.class);
        var next=write(session(0),"RATE","REMEMBERED");assertThat(next.get("isReplay").asBoolean()).isFalse();assertThat(next.get("dueDate").asText()).isEqualTo("2026-11-02");
        assertThat(learning.summary(user)).containsEntry("todayNew",0L).containsEntry("todayReview",1L);
    }
    @Test void receiptFailureRollsBackAllLearningWrites() throws Exception {
        var s=session(0);long rev=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        jdbc.execute("create function fail_learning_receipt() returns trigger language plpgsql as $$ begin raise exception 'injected'; end $$");
        jdbc.execute("create trigger fail_learning_receipt before insert on mutation_receipt for each row execute function fail_learning_receipt()");
        try{assertThatThrownBy(()->write(s,"RATE","FORGOT")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger fail_learning_receipt on mutation_receipt");jdbc.execute("drop function fail_learning_receipt()");}
        assertThat(count("review_state")).isZero();assertThat(count("review_event")).isZero();assertThat(count("review_replay_allowance")).isZero();
        assertThat(current(s).get("version").asLong()).isZero();assertThat(count("session_item")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(rev);
    }
    @Test void concurrentScoresOnlyOneWinsEvenAcrossSessions() throws Exception {
        var a=session(0);var b=session(0);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=List.of(pool.submit(()->write(a,"RATE","REMEMBERED")),pool.submit(()->write(b,"RATE","FORGOT")));int success=0,conflicts=0;
            for(var t:tasks) { var r=t.get(10,java.util.concurrent.TimeUnit.SECONDS); if(r.has("conflict")) conflicts++;else success++; }
            assertThat(success).isEqualTo(1);assertThat(conflicts).isEqualTo(1);assertThat(count("review_event")).isEqualTo(1);assertThat(count("sync_conflict")).isEqualTo(1);
        }
    }
    private LearningController.Resolve resolution(JsonNode conflict,String choice,long reviewVersion) {
        return new LearningController.Resolve(UUID.randomUUID(),epoch,conflict.path("version").asLong(),reviewVersion,choice);
    }
    @Test void identicalDeviceScoresReturnReceiptWithoutAdvancingScheduleOrCounts() throws Exception {
        var a=session(0);var b=session(0);var first=write(a,"RATE","REMEMBERED");
        var cmd=command(b,"RATE","REMEMBERED");UUID id=UUID.fromString(b.path("id").asText());
        var second=learning.write(user,id,cmd);
        assertThat(second.path("duplicateScore").asBoolean()).isTrue();
        assertThat(second.path("dueDate")).isEqualTo(first.path("dueDate"));
        assertThat(learning.write(user,id,cmd)).isEqualTo(mapper.readTree(second.toString()));
        assertThat(count("review_event")).isEqualTo(1);assertThat(count("sync_conflict")).isZero();
        assertThat(learning.summary(user)).containsEntry("todayNew",1L);
        assertThat(current(b).path("current").isNull()).isTrue();
    }
    @Test void candidateIsDurableAndCorrectionReplacesOneEffectiveScoreAtomically() throws Exception {
        var a=session(0);var b=session(0);var scored=write(a,"RATE","REMEMBERED");
        policies().save(user,parameters(0,List.of(2,4,8,16,32)));
        var cmd=command(b,"RATE","FORGOT");UUID sid=UUID.fromString(b.path("id").asText());
        var candidate=learning.write(user,sid,cmd);var conflict=candidate.path("conflict");
        assertThat(learning.write(user,sid,cmd)).isEqualTo(mapper.readTree(candidate.toString()));assertThat(count("sync_conflict")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select latest_rating from review_state where user_id=?",String.class,user)).isEqualTo("REMEMBERED");
        UUID cid=UUID.fromString(conflict.path("id").asText());var rc=resolution(conflict,"CANDIDATE",1);
        var result=learning.resolve(user,cid,rc);assertThat(learning.resolve(user,cid,rc)).isEqualTo(mapper.readTree(result.toString()));
        assertThat(result.at("/conflict/resolved").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select latest_rating from review_state where user_id=?",String.class,user)).isEqualTo("FORGOT");
        assertThat(jdbc.queryForObject("select stage from review_state where user_id=?",Integer.class,user)).isEqualTo(-1);
        assertThat(count("review_event")).isEqualTo(2);assertThat(count("review_replay_allowance")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select algorithm_version from review_event where user_id=? and kind='CORRECT'",String.class,user)).isEqualTo("rules-v1");
        assertThat(jdbc.queryForObject("select algorithm_parameters->'intervals' from review_event where user_id=? and kind='CORRECT'",String.class,user)).isEqualTo("[1, 3, 7, 14, 30]");
        assertThat(learning.summary(user)).containsEntry("todayNew",1L).containsEntry("todayReview",0L);
        var latest=current(scored.path("session"));assertThat(latest.at("/round/newCount").asInt()).isEqualTo(1);
        assertThat(latest.at("/current/isReplay").asBoolean()).isTrue();assertThat(latest.path("canUndo").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from review_event e where user_id=? and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)",Integer.class,user)).isEqualTo(1);
    }
    @Test void acceptingCurrentDoesNotChangeScheduleAndCandidatesCannotOverwriteLaterPracticeOrNewDay() throws Exception {
        var a=session(0);var b=session(0);write(a,"RATE","FORGOT");var conflict=write(b,"RATE","VAGUE").path("conflict");
        UUID cid=UUID.fromString(conflict.path("id").asText());
        write(current(a),"RATE","REMEMBERED");
        assertThatThrownBy(()->learning.resolve(user,cid,resolution(conflict,"CANDIDATE",2))).isInstanceOf(ApiFailure.class);
        assertThat(count("review_event")).isEqualTo(2);assertThat(learning.conflictView(user,cid)).containsEntry("resolved",false);
        clock.value=clock.value.plus(Duration.ofDays(1));
        assertThatThrownBy(()->learning.resolve(user,cid,resolution(conflict,"CANDIDATE",2))).isInstanceOf(ApiFailure.class);
        learning.resolve(user,cid,resolution(conflict,"CURRENT",2));
        assertThat(count("review_event")).isEqualTo(2);assertThat(learning.conflictView(user,cid)).containsEntry("resolution","CURRENT");
    }
    @Test void correctingSameDayManualPracticeKeepsItsStageAndOffersOnlyOneAutomaticReplay() throws Exception {
        write(session(0),"RATE","REMEMBERED");
        var a=session(0);var b=session(0);write(a,"RATE","REMEMBERED");
        var conflict=write(b,"RATE","FORGOT").path("conflict");
        learning.resolve(user,UUID.fromString(conflict.path("id").asText()),resolution(conflict,"CANDIDATE",2));
        var latest=current(a);assertThat(latest.at("/current/isReplay").asBoolean()).isTrue();
        assertThat(latest.at("/review/stage").asInt()).isEqualTo(0);
        assertThat(learning.summary(user)).containsEntry("todayNew",1L).containsEntry("todayReview",0L);
        var finished=write(latest,"RATE","FORGOT").path("session");
        assertThat(finished.path("current").isNull()).isTrue();assertThat(count("review_replay_allowance")).isEqualTo(1);
    }
    @Test void correctingForgotRemovesUnconsumedReplayAndConflictResolutionHonorsOwnershipEpochAndRollback() throws Exception {
        var a=session(0);var b=session(0);write(a,"RATE","FORGOT");var conflict=write(b,"RATE","REMEMBERED").path("conflict");
        UUID cid=UUID.fromString(conflict.path("id").asText());var c=resolution(conflict,"CANDIDATE",1);
        assertThatThrownBy(()->learning.resolve(UUID.randomUUID(),cid,c)).isInstanceOf(RuntimeException.class);
        var owner=user(new AccountPrincipal(user,"test",""));
        mvc.perform(post("/api/sync/conflicts/"+cid+"/resolve").with(owner).contentType("application/json").content(mapper.writeValueAsString(c))).andExpect(status().isForbidden());
        UUID outsider=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash,data_epoch) values(?,?,'unused',?)",outsider,"other-"+outsider,epoch);
        mvc.perform(post("/api/sync/conflicts/"+cid+"/resolve").with(user(new AccountPrincipal(outsider,"other",""))).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(c))).andExpect(status().isNotFound());
        assertThatThrownBy(()->learning.resolve(user,cid,new LearningController.Resolve(UUID.randomUUID(),UUID.randomUUID(),0L,1L,"CURRENT"))).isInstanceOf(ApiFailure.class);
        UUID blocked=UUID.randomUUID();var fail=new LearningController.Resolve(blocked,epoch,0L,1L,"CANDIDATE");
        jdbc.execute("alter table mutation_receipt add constraint reject_p010 check(operation_id<>'"+blocked+"'::uuid)");
        try { assertThatThrownBy(()->learning.resolve(user,cid,fail)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table mutation_receipt drop constraint reject_p010"); }
        assertThat(count("review_event")).isEqualTo(1);assertThat(count("review_replay_allowance")).isEqualTo(1);
        var done=learning.resolve(user,cid,c);assertThat(done.at("/conflict/resolved").asBoolean()).isTrue();
        assertThat(current(a).path("current").isNull()).isTrue();assertThat(count("review_replay_allowance")).isZero();
        assertThat(learning.summary(user)).containsEntry("todayNew",1L);
    }
    @Test void snapshotIncludesAllBusinessScopesAndKeepsAccountAndStorageSecretsPrivate() throws Exception {
        prefer(2,10,List.of(chapter));var a=session(0);var b=session(0);write(a,"RATE","REMEMBERED");write(b,"RATE","VAGUE");
        var sync=app.getBean(com.interviewflashcards.sync.SyncService.class);
        var snapshot=mapper.valueToTree(sync.snapshot(user,true));
        assertThat(snapshot.at("/data/card").size()).isEqualTo(3);assertThat(snapshot.at("/data/draft_card").size()).isEqualTo(3);
        assertThat(snapshot.at("/data/review_event").size()).isEqualTo(1);assertThat(snapshot.at("/data/sync_conflict").size()).isEqualTo(1);
        assertThat(snapshot.at("/data/learning_preference").size()).isEqualTo(1);assertThat(snapshot.at("/data/learning_session").size()).isEqualTo(2);
        assertThat(snapshot.toString()).doesNotContain("password_hash","storage_key","raw_evidence","attempt_token");
        assertThat(snapshot.path("learningDate").asText()).isEqualTo("2026-10-31");
        var other=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'unused')",other,"other-"+other);
        assertThat(mapper.valueToTree(sync.snapshot(other,true)).at("/data/card").size()).isZero();
        var owner=user(new AccountPrincipal(user,"test",""));
        mvc.perform(get("/api/sync/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sync/snapshot").with(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.review_event.length()").value(1));
    }
    @Test void readOnlyRoutesAndSecurityValidationEpochAndEarlyConfirmation() throws Exception {
        UUID other=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'unused')",other,"other-"+other);
        var s=session(0);var owner=user(new AccountPrincipal(user,"test",""));var outsider=user(new AccountPrincipal(other,"other",""));
        mvc.perform(get("/api/learning-sessions/"+s.get("id").asText())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/learning-sessions/"+s.get("id").asText()).with(outsider)).andExpect(status().isNotFound());
        var c=command(s,"RATE","REMEMBERED");String body=mapper.writeValueAsString(c);
        mvc.perform(post("/api/learning-sessions/"+s.get("id").asText()+"/review").with(owner).contentType("application/json").content(body)).andExpect(status().isForbidden());
        ((com.fasterxml.jackson.databind.node.ObjectNode)c.payload()).put("answerRevealed",false);
        assertThatThrownBy(()->learning.write(user,UUID.fromString(s.get("id").asText()),c)).isInstanceOf(ApiFailure.class);
        write(s,"RATE","REMEMBERED");clock.value=clock.value.plus(Duration.ofDays(1));
        // Set a future due date to exercise explicit early practice confirmation.
        jdbc.update("update review_state set due_date='2026-12-01' where user_id=?",user);
        var early=session(0);var ec=command(early,"RATE","REMEMBERED");((com.fasterxml.jackson.databind.node.ObjectNode)ec.payload()).put("confirmEarly",false);
        assertThatThrownBy(()->learning.write(user,UUID.fromString(early.get("id").asText()),ec)).isInstanceOf(ApiFailure.class);
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        current(early);learning.summary(user);assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
        assertThatThrownBy(()->learning.start(user,new LearningController.Start(UUID.randomUUID(),UUID.randomUUID(),List.of(cards.getFirst())))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,List.of(UUID.randomUUID())))).isInstanceOf(ApiFailure.class);
    }

    private JsonNode trends(int weeks,int offset,int limit) {
        return mapper.valueToTree(app.getBean(LearningTrendsService.class).trends(user,weeks,offset,limit));
    }
    @Test void weeklyTrendsDeduplicateDailyLearningAndSeparateAssessmentsAcrossShanghaiMonday() throws Exception {
        clock.value=Instant.parse("2026-10-25T15:59:00Z"); // Sunday in Shanghai.
        write(session(0),"RATE","FORGOT");
        write(session(0),"RATE","REMEMBERED"); // same-day practice, not another new card or review.
        clock.value=Instant.parse("2026-10-25T16:00:00Z"); // Monday, still Sunday in UTC.
        write(session(0),"RATE","FORGOT");
        write(session(0),"RATE","REMEMBERED");
        write(session(1),"RATE","VAGUE");
        var t=trends(2,0,20);
        assertThat(t.path("from").asText()).isEqualTo("2026-10-19");
        assertThat(t.path("to").asText()).isEqualTo("2026-10-26");
        assertThat(t.path("learningTimezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(t.at("/weeks/0/newCount").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/0/reviewCount").asInt()).isZero();
        assertThat(t.at("/weeks/0/assessments").asInt()).isEqualTo(2);
        assertThat(t.at("/weeks/1/weekStart").asText()).isEqualTo("2026-10-26");
        assertThat(t.at("/weeks/1/weekEnd").asText()).isEqualTo("2026-10-26");
        assertThat(t.at("/weeks/1/newCount").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/1/reviewCount").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/1/learningDays").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/1/assessments").asInt()).isEqualTo(3);
        assertThat(t.at("/weeks/1/forgot").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/1/vague").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/1/remembered").asInt()).isEqualTo(1);
        assertThat(t.at("/forgotten/items/0/lapseCount").asInt()).isEqualTo(2);
        assertThat(learning.summary(user)).containsEntry("todayNew",1L).containsEntry("todayReview",1L);
        clock.value=clock.value.plus(Duration.ofDays(1));write(session(0),"RATE","REMEMBERED");
        assertThat(trends(2,0,20).at("/weeks/1/reviewCount").asInt()).isEqualTo(2);
    }
    @Test void trendsRecomputeUndoCorrectionsAndUseAllHistoryForFirstLearning() throws Exception {
        clock.value=Instant.parse("2026-09-01T04:00:00Z");
        write(session(0),"RATE","FORGOT");
        clock.value=Instant.parse("2026-10-31T04:00:00Z");
        var a=session(0);var b=session(0);
        var c=command(a,"RATE","REMEMBERED");
        learning.write(user,UUID.fromString(a.path("id").asText()),c);
        learning.write(user,UUID.fromString(a.path("id").asText()),c); // exact retry.
        var conflict=write(b,"RATE","FORGOT").path("conflict");
        learning.resolve(user,UUID.fromString(conflict.path("id").asText()),resolution(conflict,"CANDIDATE",2));
        var t=trends(1,0,20);
        assertThat(t.at("/weeks/0/newCount").asInt()).isZero();
        assertThat(t.at("/weeks/0/reviewCount").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/0/assessments").asInt()).isEqualTo(1);
        assertThat(t.at("/weeks/0/remembered").asInt()).isZero();
        assertThat(t.at("/weeks/0/forgot").asInt()).isEqualTo(1);
        assertThat(t.at("/chapters/0/learnedBefore").asInt()).isEqualTo(1);
        assertThat(t.at("/forgotten/items/0/lapseCount").asInt()).isEqualTo(2);
        var scored=write(session(1),"RATE","FORGOT").path("session");
        write(scored,"UNDO",null);
        t=trends(1,0,20);
        assertThat(t.at("/weeks/0/newCount").asInt()).isZero();
        assertThat(t.at("/forgotten/total").asInt()).isEqualTo(1);
        assertThat(t.at("/chapters/0/learned").asInt()).isEqualTo(1);
    }
    @Test void chapterChangesCompareBaselineAndIncludePausedChaptersAndStableForgottenPagination() throws Exception {
        clock.value=Instant.parse("2026-10-25T04:00:00Z");
        write(session(0),"RATE","FORGOT");write(session(1),"RATE","REMEMBERED");
        clock.value=Instant.parse("2026-10-31T04:00:00Z");
        write(session(0),"RATE","FORGOT");write(session(0),"RATE","REMEMBERED");
        write(session(1),"RATE","FORGOT");write(session(2),"RATE","REMEMBERED");
        prefer(5,20,List.of());
        var t=trends(1,0,1);var ch=t.at("/chapters/0");
        assertThat(ch.path("selected").asBoolean()).isFalse();
        assertThat(ch.path("learnedBefore").asInt()).isEqualTo(2);
        assertThat(ch.path("learned").asInt()).isEqualTo(3);
        assertThat(ch.path("weakBefore").asInt()).isEqualTo(1);
        assertThat(ch.path("weak").asInt()).isEqualTo(1);
        assertThat(ch.path("remembered").asInt()).isEqualTo(2);
        assertThat(t.at("/forgotten/total").asInt()).isEqualTo(2);
        assertThat(t.at("/forgotten/items/0/id").asText()).isEqualTo(cards.get(0).toString());
        assertThat(trends(1,1,1).at("/forgotten/items/0/id").asText()).isEqualTo(cards.get(1).toString());
        assertThat(trends(1,2,1).at("/forgotten/items").isEmpty()).isTrue();
    }
    @Test void trendsAreReadOnlyPrivateValidatedAndZeroFillEmptyWeeks() throws Exception {
        var owner=user(new AccountPrincipal(user,"test",""));
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        var t=trends(8,0,20);
        assertThat(t.path("weeks").size()).isEqualTo(8);
        for(var w:t.path("weeks")) assertThat(w.path("assessments").asInt()).isZero();
        assertThat(t.at("/forgotten/total").asInt()).isZero();
        assertThat(t.at("/chapters/0/total").asInt()).isEqualTo(3);
        mvc.perform(get("/api/learning-trends")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/learning-trends").with(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.weeks.length()").value(8));
        for(String query:List.of("weeks=0","weeks=53","weeks=abc","offset=-1","limit=0","limit=101"))
            mvc.perform(get("/api/learning-trends?"+query).with(owner)).andExpect(status().isBadRequest());
        write(session(0),"RATE","FORGOT");
        UUID other=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'unused')",other,"other-"+other);
        mvc.perform(get("/api/learning-trends").with(user(new AccountPrincipal(other,"other",""))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.forgotten.total").value(0)).andExpect(jsonPath("$.data.chapters.length()").value(0));
        long scoredRevision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        trends(1,0,20);assertThat(scoredRevision).isGreaterThan(revision);
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(scoredRevision);
    }
}
