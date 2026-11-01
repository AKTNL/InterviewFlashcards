package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.cards.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.learning.*;
import com.interviewflashcards.sync.SyncService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeckDataIT {
    @TempDir static Path directory;
    final String schema="deck_data_it_"+UUID.randomUUID().toString().replace("-","");
    ServletWebServerApplicationContext app;
    JdbcTemplate jdbc; ObjectMapper mapper; DeckDataService data; LearningService learning; MockMvc mvc;
    UUID user,epoch,deck,otherDeck,card,otherCard,source,chapter; JsonNode session;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--app.backup.poll-ms=3600000",
            "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema,
            "--app.account.username=deck-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class); mapper=app.getBean(ObjectMapper.class); data=app.getBean(DeckDataService.class);learning=app.getBean(LearningService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()){s.execute("drop schema if exists "+schema+" cascade");}
    }
    UUID account() { UUID u=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'test-only')",u,u.toString());return u; }
    UUID seedDeck() {
        UUID d=UUID.randomUUID(),m=UUID.randomUUID(),ch=UUID.randomUUID(),s=UUID.randomUUID(),j=UUID.randomUUID(),draft=UUID.randomUUID(),c=UUID.randomUUID();
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'source.pdf',?,1,1,?)",s,user,"a".repeat(32)+s.toString().replace("-",""),UUID.randomUUID());
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'题库')",d,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",m,user,d);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'基础','基础','IMPORTED')",ch,user,m);
        jdbc.update("insert into import_job(id,user_id,source_id,deck_id,status) values(?,?,?,?,'READY')",j,user,s,d);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'1','问题')",draft,user,ch,j);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,'问题')",c,user,ch,draft);
        jdbc.update("insert into personal_annotation(id,user_id,card_id,notes,favorite) values(?,?,?,'保留笔记',true)",UUID.randomUUID(),user,c);
        jdbc.update("insert into chapter_check_progress(id,user_id,chapter_id,current_draft_id) values(?,?,?,?)",UUID.randomUUID(),user,ch,draft);
        jdbc.update("insert into source_span(id,user_id,source_id,draft_id,physical_page) values(?,?,?,?,1)",UUID.randomUUID(),user,s,draft);
        jdbc.update("insert into source_span(id,user_id,source_id,card_id,physical_page) values(?,?,?,?,1)",UUID.randomUUID(),user,s,c);
        jdbc.update("insert into follow_up(id,user_id,draft_id,content) values(?,?,?,'{}')",UUID.randomUUID(),user,draft);
        jdbc.update("insert into follow_up(id,user_id,card_id,content) values(?,?,?,'{}')",UUID.randomUUID(),user,c);
        source=s;chapter=ch;card=c;return d;
    }
    @BeforeEach void seed() throws Exception {
        user=account();epoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user);
        otherDeck=seedDeck();otherCard=card;deck=seedDeck();
        session=learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,List.of(otherCard,card))).path("session");
        session=rate(session,"REMEMBERED").path("session");session=rate(session,"FORGOT").path("session");
    }
    JsonNode rate(JsonNode s,String rating) throws Exception {
        var p=mapper.createObjectNode().put("itemId",s.at("/current/id").asText()).put("expectedReviewVersion",s.at("/current/expectedVersion").asLong()).put("answerRevealed",true).put("confirmEarly",true).put("rating",rating);
        return learning.write(user,UUID.fromString(s.path("id").asText()),new LearningController.Write(UUID.randomUUID(),epoch,s.path("version").asLong(),"RATE",p));
    }
    DeckDataController.Confirm command(String action) { return new DeckDataController.Confirm(UUID.randomUUID(),epoch,(Long)data.preview(user,deck).get("revision"),action,true); }
    long count(String table,UUID id) { return jdbc.queryForObject("select count(*) from "+table+" where user_id=? and card_id=?",Long.class,user,id); }
    @Test void resetPreservesContentAndOtherDeckHistoryAndInvalidatesMixedSessions() throws Exception {
        var before=jdbc.queryForMap("select * from review_state where user_id=? and card_id=?",user,otherCard);
        var cmd=command("RESET");var result=data.confirm(user,deck,cmd);
        assertThat(data.confirm(user,deck,cmd)).isEqualTo(mapper.readTree(result.toString()));
        assertThat(count("review_state",card)).isZero();assertThat(count("review_event",card)).isZero();assertThat(count("review_replay_allowance",card)).isZero();
        var trends=mapper.valueToTree(app.getBean(com.interviewflashcards.learning.LearningTrendsService.class).trends(user,8,0,20));
        assertThat(trends.at("/forgotten/total").asInt()).isZero();
        var resetChapter=java.util.stream.StreamSupport.stream(trends.path("chapters").spliterator(),false)
            .filter(ch->ch.path("id").asText().equals(chapter.toString())).findFirst().orElseThrow();
        assertThat(resetChapter.path("learned").asInt()).isZero();
        assertThat(jdbc.queryForMap("select * from review_state where user_id=? and card_id=?",user,otherCard)).isEqualTo(before);
        assertThat(count("personal_annotation",card)).isOne();assertThat(jdbc.queryForObject("select favorite from personal_annotation where user_id=? and card_id=?",Boolean.class,user,card)).isTrue();
        assertThat(data.preview(user,deck).get("counts").toString()).contains("learned=0");
        var s=learning.session(user,UUID.fromString(session.path("id").asText()));assertThat(s.get("status")).isEqualTo("INVALIDATED");assertThat(s.get("current")).isNull();assertThat(s.get("expired")).isEqualTo(true);
        assertThatThrownBy(()->rate(session,"REMEMBERED")).isInstanceOf(ApiFailure.class);
        var next=learning.start(user,new LearningController.Start(UUID.randomUUID(),epoch,List.of(card))).path("session");
        assertThat(rate(next,"REMEMBERED").path("session").path("events")).hasSize(1);
    }
    @Test void deleteRemovesOnlyDeckRelationsRetainsPdfAndAllowsReceiptRecovery() throws Exception {
        var cmd=command("DELETE");var result=data.confirm(user,deck,cmd);
        assertThat(data.confirm(user,deck,cmd)).isEqualTo(mapper.readTree(result.toString()));
        assertThat(data.decks(user)).extracting(r->r.get("id")).containsExactly(otherDeck);
        assertThat(jdbc.queryForObject("select count(*) from card where user_id=? and id=?",Long.class,user,card)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_document where user_id=? and id=?",Long.class,user,source)).isOne();
        assertThat(jdbc.queryForObject("select error_code from import_job where user_id=? and source_id=?",String.class,user,source)).isEqualTo("DECK_DELETED");
        assertThat(count("review_state",otherCard)).isOne();assertThat(count("review_event",otherCard)).isOne();
        var trends=mapper.valueToTree(app.getBean(com.interviewflashcards.learning.LearningTrendsService.class).trends(user,8,0,20));
        assertThat(trends.path("chapters").size()).isEqualTo(1);
        assertThat(trends.at("/forgotten/total").asInt()).isZero();
        var snapshot=app.getBean(SyncService.class).snapshot(user,true);assertThat(snapshot.get("revision")).isEqualTo(result.at("/mutation/revision").asLong());
        assertThatThrownBy(()->data.preview(user,deck)).isInstanceOf(ApiFailure.class);
    }
    @Test void stalePreviewAndWrongEpochCannotDeleteNewChanges() throws Exception {
        var cmd=command("DELETE");jdbc.update("update app_user set revision=revision+1 where id=?",user);
        assertThatThrownBy(()->data.confirm(user,deck,cmd)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.DATA_PREVIEW_STALE));
        var wrong=new DeckDataController.Confirm(UUID.randomUUID(),UUID.randomUUID(),cmd.expectedRevision(),"RESET",true);
        assertThatThrownBy(()->data.confirm(user,deck,wrong)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code()).isEqualTo(ErrorCode.EPOCH_EXPIRED));
        assertThat(count("review_state",card)).isOne();
    }
    @Test void authorizationCsrfAndExplicitConfirmationAreEnforced() throws Exception {
        var principal=new AccountPrincipal(user,"test","unused");String body=mapper.writeValueAsString(command("DELETE"));
        mvc.perform(post("/api/deck-data/"+deck).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/deck-data/"+deck).with(user(principal)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/deck-data/"+deck+"/preview").with(user(new AccountPrincipal(account(),"other","unused")))) .andExpect(status().isNotFound());
        var no=new DeckDataController.Confirm(UUID.randomUUID(),epoch,command("DELETE").expectedRevision(),"DELETE",false);
        mvc.perform(post("/api/deck-data/"+deck).with(user(principal)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(no))).andExpect(status().isBadRequest());
        assertThat(count("review_state",card)).isOne();
    }
    @Test void lateDatabaseFailureRollsBackHistorySessionContentAndReceipt() throws Exception {
        var cmd=command("DELETE");
        jdbc.execute("create function fail_deck_delete() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'");
        jdbc.execute("create trigger fail_deck_delete before delete on deck for each row execute function fail_deck_delete()");
        try { assertThatThrownBy(()->data.confirm(user,deck,cmd)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger fail_deck_delete on deck");jdbc.execute("drop function fail_deck_delete()"); }
        assertThat(count("review_state",card)).isOne();assertThat(count("review_event",card)).isOne();assertThat(count("session_item",card)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from learning_session where user_id=? and id=?",String.class,user,UUID.fromString(session.path("id").asText()))).isNotEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("select count(*) from mutation_receipt where user_id=? and operation_id=?",Long.class,user,cmd.operationId())).isZero();
    }
}
