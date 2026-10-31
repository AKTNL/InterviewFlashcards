package com.interviewflashcards;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.auth.AccountPrincipal;
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
class ChapterReviewIT {
    @TempDir static Path directory;
    private final String schema="review_it_"+UUID.randomUUID().toString().replace("-","");
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private ChapterReviewService service;
    private ImportService imports;
    private MockMvc mvc;
    private UUID user,epoch,chapter,source,job;
    private List<UUID> drafts;
    @BeforeAll void start() {
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(FlashcardsApplication.class).run(
            "--server.port=0","--app.import.poll-ms=3600000","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,
            "--spring.datasource.hikari.schema="+schema,"--app.account.username=review-test","--app.account.password-hash="+new BCryptPasswordEncoder(10).encode("test-only-password"),
            "--app.storage.root="+directory.resolve("files"),"--app.storage.temporary-root="+directory.resolve("tmp"));
        jdbc=app.getBean(JdbcTemplate.class); mapper=app.getBean(ObjectMapper.class); service=app.getBean(ChapterReviewService.class); imports=app.getBean(ImportService.class);
        mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();
        user=jdbc.queryForObject("select id from app_user where username='review-test'",UUID.class);
        epoch=jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,user);
    }
    @AfterAll void stop() throws Exception {
        if(app!=null) app.close();
        try(var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"));var s=c.createStatement()) { s.execute("drop schema if exists "+schema+" cascade"); }
    }
    @BeforeEach void seed() {
        source=UUID.randomUUID();job=UUID.randomUUID();chapter=UUID.randomUUID();UUID deck=UUID.randomUUID(), module=UUID.randomUUID();
        jdbc.update("insert into source_document(id,user_id,original_filename,sha256,page_count,byte_size,storage_key) values(?,?,'test.pdf',?,3,10,?)",source,user,UUID.randomUUID().toString().replace("-","").repeat(2),UUID.randomUUID());
        jdbc.update("insert into deck(id,user_id,name) values(?,?,'test')",deck,user);
        jdbc.update("insert into module(id,user_id,deck_id,name,original_name) values(?,?,?,'Java','Java')",module,user,deck);
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) values(?,?,?,'Java 基础','Java 基础')",chapter,user,module);
        jdbc.update("insert into chapter_check_progress(id,user_id,chapter_id) values(?,?,?)",UUID.randomUUID(),user,chapter);
        jdbc.update("insert into import_job(id,user_id,source_id,status,deck_id,raw_evidence) values(?,?,?,'READY',?,'{\"pages\":[{\"page\":1,\"width\":600,\"height\":800}]}')",job,user,source,deck);
        drafts=new ArrayList<>();
        for(int i=0;i<3;i++) {
            UUID draft=UUID.randomUUID(); drafts.add(draft);
            String blocks="[{\"type\":\"SOURCE_TEXT\",\"page\":1,\"textSpans\":[{\"text\":\"完整答案"+i+"\",\"bbox\":[0,0,10,10]}],\"images\":[]}]";
            jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,content_blocks,original_content,check_flags,sort_order) values(?,?,?,?,?,?,?::jsonb,?::jsonb,'[{\"kind\":\"CHECK\",\"reason\":\"核对原文\"}]',?)",draft,user,chapter,job,"q"+i,"问题"+i,blocks,blocks,i);
            jdbc.update("insert into source_span(id,user_id,source_id,draft_id,physical_page,region,sort_order) values(?,?,?,?,?,'[0,0,100,100]',?)",UUID.randomUUID(),user,source,draft,i+1,i);
            jdbc.update("insert into follow_up(id,user_id,draft_id,content) values(?,?,?,'{\"title\":\"追问\",\"page\":1,\"text\":\"完整追问\"}')",UUID.randomUUID(),user,draft);
        }
    }
    private long version() { return ((Number)service.view(user,chapter).get("version")).longValue(); }
    private ChapterReviewController.Write command(String action,Object payload,long version,UUID operation) { return new ChapterReviewController.Write(operation,epoch,version,action,mapper.valueToTree(payload)); }
    private JsonNode write(String action,Object payload) throws Exception { return service.write(user,chapter,command(action,payload,version(),UUID.randomUUID())); }
    private Map<String,Object> value(UUID id) {
        var d=imports.draft(user,id); var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("question","contentBlocks","flags","sources","followups")) result.put(key,d.get(key));
        return result;
    }
    @Test void mergeRetainsEverySourceFollowupAndExplainsCountSeparately() throws Exception {
        var firstOriginal=imports.draft(user,drafts.getFirst()).get("originalContent");
        write("MERGE",Map.of("draftIds",drafts.subList(0,2),"reason","同一道跨页题"));
        var merged=imports.draft(user,drafts.getFirst());
        assertThat((List<?>)merged.get("sources")).hasSize(2); assertThat((List<?>)merged.get("followups")).hasSize(2);
        assertThat(((JsonNode)merged.get("originalContent")).size()).isEqualTo(2);
        assertThat(((JsonNode)merged.get("originalContent")).get(0)).isEqualTo(((JsonNode)firstOriginal).get(0));
        assertThat(imports.draft(user,drafts.get(1)).get("excluded")).isEqualTo(true);
        assertThat(service.view(user,chapter)).containsEntry("activeCount",2L).containsEntry("userCountDelta",-1);
        assertThat(jdbc.queryForObject("select evidence::text from draft_edit_operation where chapter_id=?",String.class,chapter)).contains("完整答案0","完整答案1","sources");
    }
    @Test void splitAssociatesNewDraftAndConfirmationLinksFormalCards() throws Exception {
        var one=value(drafts.getFirst()); var two=value(drafts.getFirst()); one.put("question","主问题");two.put("question","独立追问");
        write("SPLIT",Map.of("draftId",drafts.getFirst(),"parts",List.of(one,two),"reason","追问单独成题"));
        UUID child=jdbc.queryForObject("select id from draft_card where parent_draft_id=?",UUID.class,drafts.getFirst());
        assertThat(service.view(user,chapter)).containsEntry("activeCount",4L).containsEntry("userCountDelta",1);
        write("CONFIRM",Map.of("allowFlagged",true));
        UUID parent=jdbc.queryForObject("select id from card where draft_id=?",UUID.class,drafts.getFirst());
        assertThat(jdbc.queryForObject("select parent_card_id from card where draft_id=?",UUID.class,child)).isEqualTo(parent);
        assertThat(jdbc.queryForObject("select count(*) from source_span s join card c on c.id=s.card_id where c.chapter_id=?",Integer.class,chapter)).isEqualTo(4);
    }
    @Test void flaggedConfirmationNeedsExplicitChoiceAndRepeatsReturnStableMapping() throws Exception {
        UUID untouched=UUID.randomUUID();
        jdbc.update("insert into chapter(id,user_id,module_id,name,original_name) select ?,user_id,module_id,'JVM','JVM' from chapter where id=?",untouched,chapter);
        long before=version();
        assertThatThrownBy(()->write("CONFIRM",Map.of("allowFlagged",false))).isInstanceOf(ApiFailure.class);
        assertThat(version()).isEqualTo(before);
        var cmd=command("CONFIRM",Map.of("allowFlagged",true),before,UUID.randomUUID());
        var first=mapper.readTree(service.write(user,chapter,cmd).toString());
        assertThat(service.write(user,chapter,cmd)).isEqualTo(first);
        var repeated=service.write(user,chapter,command("CONFIRM",Map.of("allowFlagged",true),before,UUID.randomUUID()));
        assertThat(repeated.at("/chapter/cards")).isEqualTo(first.at("/chapter/cards"));
        assertThat(jdbc.queryForObject("select count(*) from card where chapter_id=?",Integer.class,chapter)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from card where chapter_id=? and needs_check",Integer.class,chapter)).isEqualTo(3);
        assertThat(service.cards(user,chapter)).hasSize(3);
        assertThat(service.view(user,untouched).get("checkStatus")).isEqualTo("PENDING");
    }
    @Test void confirmationFailureAfterFirstCardRollsBackCardsRelationsRevisionAndReceipt() throws Exception {
        long before=version(); long revision=jdbc.queryForObject("select revision from app_user where id=?",Long.class,user);
        UUID operation=UUID.randomUUID(); var cmd=command("CONFIRM",Map.of("allowFlagged",true),before,operation);
        jdbc.execute("create function fail_review_card() returns trigger language plpgsql as $$ begin if NEW.draft_id='"+drafts.get(1)+"'::uuid then raise exception 'injected failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger fail_review_card before insert on card for each row execute function fail_review_card()");
        try { assertThatThrownBy(()->service.write(user,chapter,cmd)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger fail_review_card on card");jdbc.execute("drop function fail_review_card()"); }
        assertThat(jdbc.queryForObject("select count(*) from card where chapter_id=?",Integer.class,chapter)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mutation_receipt where operation_id=?",Integer.class,operation)).isZero();
        assertThat(jdbc.queryForObject("select revision from app_user where id=?",Long.class,user)).isEqualTo(revision); assertThat(version()).isEqualTo(before);
        assertThat(service.view(user,chapter).get("checkStatus")).isEqualTo("PENDING");
        assertThat(service.write(user,chapter,cmd).at("/chapter/cards").size()).isEqualTo(3);
    }
    @Test void importedCorrectionsKeepStableCardOriginalEvidenceAndReviewState() throws Exception {
        write("CONFIRM",Map.of("allowFlagged",true));
        UUID draft=drafts.getFirst();UUID card=jdbc.queryForObject("select id from card where draft_id=?",UUID.class,draft);
        UUID state=UUID.randomUUID(); jdbc.update("insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,latest_rating) values(?,?,?,now(),now(),'2026-11-05','REMEMBERED')",state,user,card);
        var original=jdbc.queryForObject("select original_content::text from card where id=?",String.class,card);
        var correction=value(draft); correction.put("question","修正后的问题");correction.put("flags",List.of());correction.put("sources",List.of(Map.of("page",2,"bbox",List.of(1,2,300,400))));
        var blocks=mapper.valueToTree(correction.get("contentBlocks"));((com.fasterxml.jackson.databind.node.ObjectNode)blocks.get(0)).put("page",2);correction.put("contentBlocks",blocks);
        write("EDIT",Map.of("draftId",draft,"value",correction,"reason","人工核对纠错"));
        assertThat(jdbc.queryForObject("select id from card where draft_id=?",UUID.class,draft)).isEqualTo(card);
        assertThat(jdbc.queryForObject("select question from card where id=?",String.class,card)).isEqualTo("修正后的问题");
        assertThat(jdbc.queryForObject("select original_content::text from card where id=?",String.class,card)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select needs_check from card where id=?",Boolean.class,card)).isFalse();
        assertThat(jdbc.queryForObject("select due_date::text from review_state where id=?",String.class,state)).isEqualTo("2026-11-05");
        assertThat(jdbc.queryForObject("select physical_page from source_span where card_id=?",Integer.class,card)).isEqualTo(2);
        assertThatThrownBy(()->write("EXCLUDE",Map.of("draftId",draft,"reason","不可删除正式内容"))).isInstanceOf(ApiFailure.class);
    }
    @Test void editingConflictReturnsCurrentVersionAndRejectsInvalidBoundaryAndOldEpoch() throws Exception {
        var payload=Map.of("draftId",drafts.getFirst(),"value",value(drafts.getFirst()),"reason","首设备修改");long old=version();
        write("EDIT",payload);
        assertThatThrownBy(()->service.write(user,chapter,command("EDIT",payload,old,UUID.randomUUID()))).isInstanceOf(ApiFailure.class);
        var bad=value(drafts.getFirst());bad.put("sources",List.of(Map.of("page",4,"bbox",List.of(0,0,1,1))));
        long current=version();
        assertThatThrownBy(()->write("EDIT",Map.of("draftId",drafts.getFirst(),"value",bad,"reason","无效页码"))).isInstanceOf(ApiFailure.class);
        bad.put("sources",List.of(Map.of("page",1,"bbox",List.of(0,0,601,100))));
        assertThatThrownBy(()->write("EDIT",Map.of("draftId",drafts.getFirst(),"value",bad,"reason","超出页面"))).isInstanceOf(ApiFailure.class);
        assertThat(version()).isEqualTo(current);
        assertThatThrownBy(()->service.write(user,chapter,new ChapterReviewController.Write(UUID.randomUUID(),UUID.randomUUID(),current,"PROGRESS",mapper.valueToTree(Map.of("draftId",drafts.getFirst()))))).isInstanceOf(ApiFailure.class);
    }
    @Test void progressSurvivesContextRestartAndAllReviewRoutesEnforceOwnership() throws Exception {
        write("PROGRESS",Map.of("draftId",drafts.get(1)));
        UUID savedChapter=chapter; UUID savedDraft=drafts.get(1);
        app.close();start();
        assertThat(service.view(user,savedChapter)).containsEntry("currentDraftId",savedDraft).containsEntry("checkStatus","IN_PROGRESS");
        UUID other=UUID.randomUUID();jdbc.update("insert into app_user(id,username,password_hash) values(?,?,?)",other,"other-"+other,"unused");
        mvc.perform(get("/api/chapters/"+chapter+"/review")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chapters/"+chapter+"/review").with(user(new AccountPrincipal(other,"other","")))).andExpect(status().isNotFound());
        mvc.perform(get("/api/chapters/"+chapter+"/cards").with(user(new AccountPrincipal(other,"other","")))).andExpect(status().isNotFound());
        mvc.perform(post("/api/chapters/"+chapter+"/review").with(user(new AccountPrincipal(other,"other",""))).with(csrf())
            .contentType("application/json").content(mapper.writeValueAsString(new ChapterReviewController.Write(UUID.randomUUID(),jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,other),0L,"CONFIRM",mapper.valueToTree(Map.of("allowFlagged",true)))))).andExpect(status().isNotFound());
    }
    @Test void abandoningRemainingDraftsRequiresConfirmationAndKeepsOtherImportedChapters() throws Exception {
        write("CONFIRM",Map.of("allowFlagged",true));UUID importedChapter=chapter;
        seed();
        assertThatThrownBy(()->write("ABANDON",Map.of("confirmed",false,"reason","暂不导入"))).isInstanceOf(ApiFailure.class);
        write("ABANDON",Map.of("confirmed",true,"reason","不在学习范围"));
        assertThat(service.view(user,chapter)).containsEntry("abandoned",true).containsEntry("activeCount",0L).containsEntry("userCountDelta",-3);
        assertThat(jdbc.queryForObject("select count(*) from card where chapter_id=?",Integer.class,importedChapter)).isEqualTo(3);
        assertThatThrownBy(()->write("CONFIRM",Map.of("allowFlagged",true))).isInstanceOf(ApiFailure.class);
    }
    @Test void concurrentDeviceConfirmationsPublishOnlyOneMapping() throws Exception {
        var first=command("CONFIRM",Map.of("allowFlagged",true),0,UUID.randomUUID());
        var second=command("CONFIRM",Map.of("allowFlagged",true),0,UUID.randomUUID());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->service.write(user,chapter,first));
            var b=pool.submit(()->service.write(user,chapter,second));
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS).at("/chapter/cards"))
                .isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS).at("/chapter/cards"));
        }
        assertThat(jdbc.queryForObject("select count(*) from card where chapter_id=?",Integer.class,chapter)).isEqualTo(3);
        assertThat(version()).isEqualTo(1);
    }
}
