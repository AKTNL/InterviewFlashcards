package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.cards.*;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.importing.*;
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
class CardLibraryIT {
    @TempDir static Path directory;
    private final String schema="cards_it_"+UUID.randomUUID().toString().replace("-","");
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc; private ObjectMapper mapper; private CardService cards; private ChapterReviewService review; private MockMvc mvc;
    private UUID user, epoch, deck, module, chapter; private List<UUID> ids;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,
            "--spring.datasource.hikari.schema="+schema,"--app.account.username=cards-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class); mapper=app.getBean(ObjectMapper.class); cards=app.getBean(CardService.class);review=app.getBean(ChapterReviewService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
    }
    @AfterAll void stop() throws Exception {
        if(app!=null) app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()) { s.execute("drop schema if exists "+schema+" cascade"); }
    }
    @BeforeEach void seed() throws Exception {
        user=UUID.randomUUID(); epoch=UUID.randomUUID();deck=UUID.randomUUID();module=UUID.randomUUID();chapter=UUID.randomUUID();
        UUID source=UUID.randomUUID(),job=UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash,data_epoch) values(?,?,?,?)",user,"user-"+user,"unused",epoch);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'source.pdf',?,3,10,?)",source,user,"a".repeat(64),UUID.randomUUID());
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",deck,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,user,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'Java 基础','Java')",chapter,user,module);
        jdbc.update("insert into chapter_check_progress(id,user_id,chapter_id) values(?,?,?)",UUID.randomUUID(),user,chapter);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id) values(?,?,?,'READY',?)",job,user,source,deck);
        for(int i=0;i<25;i++) {
            UUID draft=UUID.randomUUID();
            String blocks="[{\"type\":\"SOURCE_TEXT\",\"page\":1,\"textSpans\":[{\"text\":\"完整跨页答案"+i+"\",\"bbox\":[0,0,100,100]}],\"images\":[]}]";
            jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,content_blocks,original_content,check_flags,sort_order) values(?,?,?,?,?,?,?::jsonb,?::jsonb,'[{\"kind\":\"CHECK\",\"reason\":\"核对\"}]',?)",draft,user,chapter,job,"q"+i,i==0?"HashMap 100%_!":("问题"+i),blocks,blocks,i);
            for(int page=1;page<=2;page++) jdbc.update("insert into source_span(id,user_id,source_id,draft_id,physical_page,region,sort_order) values(?,?,?,?,?,'[0,0,100,100]',?)",UUID.randomUUID(),user,source,draft,page,page);
            jdbc.update("insert into follow_up(id,user_id,draft_id,content) values(?,?,?,'{\"title\":\"追问\",\"page\":2,\"text\":\"完整追问答案\"}')",UUID.randomUUID(),user,draft);
        }
        review.write(user,chapter,new ChapterReviewController.Write(UUID.randomUUID(),epoch,0L,"CONFIRM",mapper.valueToTree(Map.of("allowFlagged",true))));
        ids=jdbc.queryForList("select c.id from card c join draft_card d on d.id=c.draft_id where c.user_id=? order by d.sort_order",UUID.class,user);
    }
    private Map<String,Object> list(String q,String filter,int offset) { return cards.list(user,deck,module,chapter,q,filter,offset,20); }
    private Map<String,Object> annotation(String notes,boolean favorite) { return Map.of("notes",notes,"hint","提示","memoryPoints","要点","favorite",favorite); }
    private CardController.Write command(String action,Object payload,long version) { return new CardController.Write(UUID.randomUUID(),epoch,version,action,mapper.valueToTree(payload)); }
    private JsonNode write(int index,String action,Object payload,long version) throws Exception { return cards.write(user,ids.get(index),command(action,payload,version)); }
    private UUID copyDeck(UUID owner,String title) {
        UUID copy=UUID.randomUUID(),m=UUID.randomUUID(),ch=UUID.randomUUID(),s=UUID.randomUUID(),j=UUID.randomUUID(),draft=UUID.randomUUID(),card=UUID.randomUUID();
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'面试')",copy,owner);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",m,owner,copy);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,check_status) values(?,?,?,'Java 基础','Java','IMPORTED')",ch,owner,m);
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key,shared_copy) values(?,?,'copy.pdf',?,3,10,?,true)",s,owner,"a".repeat(64),UUID.randomUUID());
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id) values(?,?,?,'READY',?)",j,owner,s,copy);
        jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question) values(?,?,?,?,'copy',?)",draft,owner,ch,j,title);
        jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question) values(?,?,?,?,?)",card,owner,ch,draft,title);
        return copy;
    }
    @Test void independentDeckOverviewIncludesEmptyDecksSameFileHintsAndScopedProgress() throws Exception {
        UUID copy=copyDeck(user,"  HASHMAP\t100%_! "),empty=UUID.randomUUID();
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'空题库')",empty,user);
        var today=app.getBean(LearningTime.class).today();
        jdbc.update("insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,latest_rating) values(?,?,?,now(),now(),?,'VAGUE')",UUID.randomUUID(),user,ids.getFirst(),today);
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        var value=mapper.valueToTree(app.getBean(DeckManagementService.class).overview(user));
        assertThat(value.path("decks").size()).isEqualTo(3);
        var byId=new HashMap<String,JsonNode>();value.path("decks").forEach(d->byId.put(d.path("id").asText(),d));
        assertThat(byId.get(deck.toString()).path("learned").asInt()).isEqualTo(1);
        assertThat(byId.get(deck.toString()).path("due").asInt()).isEqualTo(1);
        assertThat(byId.get(deck.toString()).path("weak").asInt()).isEqualTo(1);
        assertThat(byId.get(copy.toString()).path("learned").asInt()).isZero();
        assertThat(byId.get(copy.toString()).path("duplicates").asInt()).isEqualTo(1);
        assertThat(byId.get(empty.toString()).path("total").asInt()).isZero();
        assertThat(byId.get(empty.toString()).path("chapters").asInt()).isZero();
        assertThat(value.path("sources").size()).isEqualTo(2);
        value.path("sources").forEach(s->assertThat(s.path("sameFileDecks").asInt()).isEqualTo(1));
        assertThat(cards.list(user,copy,null,null,"","DUPLICATE",0,20).get("total")).isEqualTo(1L);
        assertThat(cards.list(user,null,null,null,"","DUPLICATE",0,1).get("total")).isEqualTo(2L);
        var page=mapper.valueToTree(cards.list(user,copy,null,null,"","UNLEARNED",0,20));
        assertThat(page.at("/items/0/deckId").asText()).isEqualTo(copy.toString());
        assertThat(page.at("/items/0/deckName").asText()).isEqualTo("面试");
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
        var data=app.getBean(DeckDataService.class);
        data.confirm(user,copy,new DeckDataController.Confirm(UUID.randomUUID(),epoch,revision,"DELETE",true));
        assertThat(cards.list(user,null,null,null,"","DUPLICATE",0,20).get("total")).isEqualTo(0L);
        assertThat(list("","LEARNED",0).get("total")).isEqualTo(1L);
        var remaining=mapper.valueToTree(app.getBean(DeckManagementService.class).overview(user));
        assertThat(remaining.path("sources").size()).isEqualTo(1);
        assertThat(remaining.at("/sources/0/sameFileDecks").asInt()).isZero();
    }
    @Test void duplicateHintsAndOverviewNeverIncludeOtherAccountsOrDrafts() throws Exception {
        UUID other=UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'unused')",other,"other-"+other);
        copyDeck(other,"HashMap 100%_!");
        assertThat(list("","DUPLICATE",0).get("total")).isEqualTo(0L);
        jdbc.update("update draft_card set question='问题1' where user_id=?",user);
        assertThat(list("","DUPLICATE",0).get("total")).isEqualTo(0L);
        var owner=user(new AccountPrincipal(user,"test",""));
        mvc.perform(get("/api/cards/decks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cards/decks").with(owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.decks.length()").value(1)).andExpect(jsonPath("$.data.sources[0].sameFileDecks").value(0));
        mvc.perform(get("/api/cards?filter=DUPLICATE").with(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("update card set question='  问题 1  ' where id=?",ids.getFirst());
        assertThat(list("","DUPLICATE",0).get("total")).isEqualTo(2L);
        assertThat(list("问题","DUPLICATE",0).get("total")).isEqualTo(2L);
    }
    @Test void literalSearchScopesStablePaginationAndDraftsExcluded() {
        var first=list("","ALL",0);var last=list("","ALL",20);
        assertThat(first.get("total")).isEqualTo(25L);assertThat((List<?>)first.get("items")).hasSize(20);assertThat((List<?>)last.get("items")).hasSize(5);
        var seen=new HashSet<>();for(var page:List.of(first,last)) for(var item:(List<Map<String,Object>>)page.get("items")) assertThat(seen.add(item.get("id"))).isTrue();
        assertThat(list("100%_!","ALL",0).get("total")).isEqualTo(1L);assertThat(list("hashmap","ALL",0).get("total")).isEqualTo(1L);
        assertThat(cards.list(user,UUID.randomUUID(),null,null,"","ALL",0,20).get("total")).isEqualTo(0L);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'待确认','draft')",UUID.randomUUID(),user,module);
        assertThat(cards.catalog(user)).hasSize(2);assertThat(list("","ALL",0).get("total")).isEqualTo(25L);
    }
    @Test void filtersUsePersistedReviewStateAndServerLearningDateWithoutWriting() throws Exception {
        var today=app.getBean(LearningTime.class).today();
        for(int i=0;i<3;i++) jdbc.update("insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,latest_rating,lapse_count) values(?,?,?,now(),now(),?,?,2)",UUID.randomUUID(),user,ids.get(i),today.plusDays(i-1),i==0?"FORGOT":i==1?"VAGUE":"REMEMBERED");
        assertThat(list("","UNLEARNED",0).get("total")).isEqualTo(22L);assertThat(list("","LEARNED",0).get("total")).isEqualTo(3L);
        assertThat(list("","DUE",0).get("total")).isEqualTo(2L);assertThat(list("","WEAK",0).get("total")).isEqualTo(2L);
        write(0,"ANNOTATION",annotation("笔记",true),0);assertThat(list("","FAVORITE",0).get("total")).isEqualTo(1L);
        assertThat(list("","CHECK",0).get("total")).isEqualTo(25L);
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        for(int i=0;i<3;i++) cards.detail(user,ids.get(i));list("","DUE",0);
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("select count(*) from review_event where user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("select due_date from review_state where card_id=?",java.time.LocalDate.class,ids.get(0))).isEqualTo(today.minusDays(1));
    }
    @Test void annotationsAreIdempotentAndConflictsKeepBothVersionsAndOriginalSchedule() throws Exception {
        var command=command("ANNOTATION",annotation("设备 A",true),0);var first=cards.write(user,ids.getFirst(),command);
        assertThat(cards.write(user,ids.getFirst(),command)).isEqualTo(mapper.readTree(first.toString()));
        assertThatThrownBy(()->cards.write(user,ids.getFirst(),new CardController.Write(command.operationId(),epoch,0L,"ANNOTATION",mapper.valueToTree(annotation("重用 ID",false))))).isInstanceOf(ApiFailure.class);
        try { write(0,"ANNOTATION",annotation("设备 B",false),0); fail("conflict expected"); }
        catch(ApiFailure e) { assertThat(mapper.valueToTree(e.details()).toString()).contains("设备 A"); }
        assertThat(mapper.valueToTree(cards.detail(user,ids.getFirst())).at("/annotation/notes").asText()).isEqualTo("设备 A");
        assertThat(jdbc.queryForObject("select count(*) from mutation_receipt where user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from review_state where user_id=?",Integer.class,user)).isZero();
    }
    @Test void technicalCorrectionsTagsAndContentEditsPreserveOriginalAndCardIdentity() throws Exception {
        UUID id=ids.getFirst();var original=cards.detail(user,id).get("originalContent");
        jdbc.update("insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,latest_rating) values(?,?,?,now(),now(),'2030-01-01','REMEMBERED')",UUID.randomUUID(),user,id);
        write(0,"METADATA",Map.of("technicalCorrection","JDK 技术纠错","applicableVersion","JDK 21","tags",List.of("集合","集合","Java")),0);
        var value=mapper.valueToTree(cards.detail(user,id));assertThat(value.at("/tags").size()).isEqualTo(2);assertThat(value.at("/technicalCorrection").asText()).isEqualTo("JDK 技术纠错");
        var draft=app.getBean(ImportService.class).draft(user,UUID.fromString(value.get("draftId").asText()));var edited=new LinkedHashMap<String,Object>();
        for(String key:List.of("question","contentBlocks","sources","flags","followups")) edited.put(key,draft.get(key));edited.put("question","人工修正标题");edited.put("flags",List.of());
        review.write(user,chapter,new ChapterReviewController.Write(UUID.randomUUID(),epoch,1L,"EDIT",mapper.valueToTree(Map.of("draftId",value.get("draftId").asText(),"value",edited,"reason","对照 PDF 排版"))));
        var after=cards.detail(user,id);assertThat(after.get("originalContent")).isEqualTo(original);assertThat(after).containsEntry("id",id).containsEntry("needsCheck",false).containsEntry("technicalCorrection","JDK 技术纠错");
        assertThat(mapper.valueToTree(after).at("/review/dueDate").asText()).isEqualTo("2030-01-01");
        assertThat((List<?>)after.get("sources")).hasSize(2);assertThat(mapper.valueToTree(after).at("/followups/0/text").asText()).isEqualTo("完整追问答案");
        var emptyVersion=mapper.createObjectNode().put("technicalCorrection","").putNull("applicableVersion");emptyVersion.putArray("tags");
        write(0,"METADATA",emptyVersion,2);assertThat(cards.detail(user,id).get("applicableVersion")).isNull();
    }
    @Test void transactionFailureRollsBackAnnotationRevisionAndReceipt() throws Exception {
        long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        jdbc.execute("create function fail_annotation_receipt() returns trigger language plpgsql as $$ begin raise exception 'injected failure'; end $$");
        jdbc.execute("create trigger fail_annotation_receipt before insert on mutation_receipt for each row execute function fail_annotation_receipt()");
        try { assertThatThrownBy(()->write(0,"ANNOTATION",annotation("回滚",true),0)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger fail_annotation_receipt on mutation_receipt");jdbc.execute("drop function fail_annotation_receipt()"); }
        assertThat(jdbc.queryForObject("select count(*) from personal_annotation where user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision);
    }
    @Test void concurrentAnnotationWritesConflictInsteadOfLosingData() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->write(0,"ANNOTATION",annotation("A",true),0));var b=pool.submit(()->write(0,"ANNOTATION",annotation("B",false),0));
            int successes=0,conflicts=0;
            for(var task:List.of(a,b)) try { task.get(10,java.util.concurrent.TimeUnit.SECONDS);successes++; }
            catch(java.util.concurrent.ExecutionException e) { assertThat(e.getCause()).isInstanceOf(ApiFailure.class);conflicts++; }
            assertThat(successes).isEqualTo(1);assertThat(conflicts).isEqualTo(1);
        }
    }
    @Test void allRoutesEnforceOwnershipCsrfValidationAndEpoch() throws Exception {
        var owner=user(new AccountPrincipal(user,"test",""));UUID other=UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'unused')",other,"other-"+other);var outsider=user(new AccountPrincipal(other,"other",""));
        mvc.perform(get("/api/cards")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cards/"+ids.getFirst()).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(get("/api/cards/catalog").with(outsider)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/cards").with(outsider)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/cards?limit=101").with(owner)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/cards?filter=INVALID").with(owner)).andExpect(status().isBadRequest());
        var command=command("ANNOTATION",annotation("不应写入",true),0);
        mvc.perform(post("/api/cards/"+ids.getFirst()).with(owner).contentType("application/json").content(mapper.writeValueAsString(command))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cards/"+ids.getFirst()).with(outsider).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(new CardController.Write(UUID.randomUUID(),jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,other),0L,"ANNOTATION",command.payload())))).andExpect(status().isNotFound());
        assertThatThrownBy(()->cards.write(user,ids.getFirst(),new CardController.Write(UUID.randomUUID(),UUID.randomUUID(),0L,"ANNOTATION",command.payload()))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->write(0,"METADATA",Map.of("technicalCorrection","x","applicableVersion",false,"tags",List.of()),0)).isInstanceOf(ApiFailure.class);
    }
}
