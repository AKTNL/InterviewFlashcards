package com.interviewflashcards.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CardService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MutationService mutations;
    private final LearningTime time;
    public CardService(JdbcTemplate jdbc, ObjectMapper mapper, MutationService mutations, LearningTime time) {
        this.jdbc=jdbc; this.mapper=mapper; this.mutations=mutations; this.time=time;
    }
    private static final String JOINS="""
        from card c join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id
        join draft_card dc on dc.user_id=c.user_id and dc.id=c.draft_id
        join module m on m.user_id=ch.user_id and m.id=ch.module_id
        join deck d on d.user_id=m.user_id and d.id=m.deck_id
        left join personal_annotation a on a.user_id=c.user_id and a.card_id=c.id
        left join review_state r on r.user_id=c.user_id and r.card_id=c.id
        """;
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> catalog(UUID user) {
        return jdbc.queryForList("""
            select d.id as "deckId", d.name as "deckName", m.id as "moduleId", m.name as "moduleName",
            ch.id as "chapterId", ch.name as "chapterName", ch.check_status as "checkStatus",
            (select count(*) from card c where c.user_id=ch.user_id and c.chapter_id=ch.id) as "cardCount"
            from deck d join module m on m.user_id=d.user_id and m.deck_id=d.id
            join chapter ch on ch.user_id=m.user_id and ch.module_id=m.id
            where d.user_id=? order by d.id,m.sort_order,m.id,ch.sort_order,ch.id
            """,user);
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(UUID user, UUID deck, UUID module, UUID chapter, String q, String filter, int offset, int limit) {
        if(offset<0 || limit<1 || limit>100 || q.length()>200) invalid();
        StringBuilder where=new StringBuilder(" where c.user_id=?"); var args=new ArrayList<Object>(); args.add(user);
        for(var scope:List.of(new Object[]{"d.id",deck}, new Object[]{"m.id",module}, new Object[]{"ch.id",chapter})) {
            if(scope[1]!=null) { where.append(" and ").append(scope[0]).append("=?"); args.add(scope[1]); }
        }
        if(!q.isBlank()) {
            // Search is a literal title substring, including %, _ and backslash.
            where.append(" and c.question ilike ? escape '!' ");
            args.add("%"+q.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        where.append(switch(filter) {
            case "ALL" -> "";
            case "UNLEARNED" -> " and r.id is null";
            case "LEARNED" -> " and r.id is not null";
            case "DUE" -> { args.add(time.today()); yield " and r.due_date<=?"; }
            case "WEAK" -> " and r.latest_rating in ('FORGOT','VAGUE')";
            case "FAVORITE" -> " and a.favorite=true";
            case "CHECK" -> " and c.needs_check=true";
            default -> throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        });
        long total=jdbc.queryForObject("select count(*) "+JOINS+where,Long.class,args.toArray());
        args.add(limit);args.add(offset);
        var items=jdbc.queryForList("""
            select c.id,c.question,c.needs_check as "needsCheck",ch.name as "chapterName",m.name as "moduleName",
            coalesce(a.favorite,false) as favorite,(r.id is not null) as learned,r.due_date as "dueDate",r.latest_rating as "latestRating"
            """+JOINS+where+" order by d.id,m.sort_order,m.id,ch.sort_order,ch.id,dc.sort_order,dc.id,c.id limit ? offset ?",args.toArray());
        return obj("items",items,"total",total,"offset",offset,"limit",limit,"learningDate",time.today());
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(UUID user, UUID id) {
        var rows=jdbc.queryForList("""
            select c.*,ch.name as chapter_name,ch.version as chapter_version,m.name as module_name,d.name as deck_name,
            a.notes,a.hint,a.memory_points,a.favorite,coalesce(a.version,0) as annotation_version,
            r.due_date,r.latest_rating,r.lapse_count,r.first_learned_at
            """+JOINS+" where c.user_id=? and c.id=?",user,id);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        var c=rows.getFirst();
        var draft=jdbc.queryForMap("select check_flags from draft_card where user_id=? and id=?",user,c.get("draft_id"));
        return obj("id",id,"draftId",c.get("draft_id"),"parentCardId",c.get("parent_card_id"),"chapterId",c.get("chapter_id"),
            "chapterName",c.get("chapter_name"),"moduleName",c.get("module_name"),"deckName",c.get("deck_name"),"chapterVersion",c.get("chapter_version"),
            "question",c.get("question"),"version",c.get("version"),"needsCheck",c.get("needs_check"),"flags",json(draft.get("check_flags")),
            "originalContent",json(c.get("original_content")),"contentBlocks",json(c.get("display_content")),"displayRevision",c.get("display_revision"),
            "applicableVersion",c.get("applicable_version"),"technicalCorrection",c.get("technical_correction"),
            "annotation",obj("notes",text(c.get("notes")),"hint",text(c.get("hint")),"memoryPoints",text(c.get("memory_points")),"favorite",Boolean.TRUE.equals(c.get("favorite")),"version",c.get("annotation_version")),
            "review",obj("version",c.get("review_version"),"learned",c.get("first_learned_at")!=null,"dueDate",c.get("due_date"),"latestRating",c.get("latest_rating"),"lapseCount",c.get("lapse_count")),
            "tags",jdbc.queryForList("select t.name from tag t join card_tag ct on ct.user_id=t.user_id and ct.tag_id=t.id where ct.user_id=? and ct.card_id=? order by t.name",String.class,user,id),
            "sources",jdbc.queryForList("select s.source_id as \"sourceId\",s.physical_page as page,s.region,d.original_filename as name from source_span s join source_document d on d.user_id=s.user_id and d.id=s.source_id where s.user_id=? and s.card_id=? order by s.sort_order,s.id",user,id)
                .stream().map(s -> obj("sourceId",s.get("sourceId"),"page",s.get("page"),"bbox",json(s.get("region")),"name",s.get("name"))).toList(),
            "followups",jdbc.queryForList("select content from follow_up where user_id=? and card_id=? order by sort_order,id",user,id).stream().map(f -> json(f.get("content"))).toList());
    }
    public JsonNode write(UUID user, UUID id, CardController.Write command) throws IOException {
        return mutations.execute(user,new MutationService.Command(command.operationId(),command.dataEpoch()),"card.update",
            obj("id",id,"expectedVersion",command.expectedVersion(),"action",command.action(),"payload",command.payload()),()->{
                var current=detail(user,id); boolean annotation="ANNOTATION".equals(command.action());
                if(!annotation && !"METADATA".equals(command.action())) invalid();
                long version=((Number)(annotation?((Map<?,?>)current.get("annotation")).get("version"):current.get("version"))).longValue();
                if(version!=command.expectedVersion()) throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",current));
                var p=command.payload();
                if(annotation) {
                    String notes=field(p,"notes",50000),hint=field(p,"hint",10000),points=field(p,"memoryPoints",20000);
                    if(!p.path("favorite").isBoolean()) invalid();
                    jdbc.update("""
                        insert into personal_annotation(id,user_id,card_id,notes,hint,memory_points,favorite,version) values(?,?,?,?,?,?,?,1)
                        on conflict(user_id,card_id) do update set notes=excluded.notes,hint=excluded.hint,memory_points=excluded.memory_points,favorite=excluded.favorite,version=personal_annotation.version+1
                        """,UUID.randomUUID(),user,id,notes,hint,points,p.get("favorite").asBoolean());
                } else {
                    String correction=field(p,"technicalCorrection",50000); var applicable=p.get("applicableVersion");
                    if(applicable==null || (!applicable.isNull() && (!applicable.isTextual() || applicable.asText().length()>200))) invalid();
                    var tags=p.path("tags"); if(!tags.isArray() || tags.size()>30) invalid();
                    var names=new LinkedHashSet<String>(); for(var tag:tags) {
                        if(!tag.isTextual() || tag.asText().isBlank() || tag.asText().length()>100) invalid(); names.add(tag.asText().strip());
                    }
                    jdbc.update("update card set technical_correction=?,applicable_version=?,version=version+1 where user_id=? and id=?",
                        correction,applicable.isNull() || applicable.asText().isBlank()?null:applicable.asText().strip(),user,id);
                    jdbc.update("delete from card_tag where user_id=? and card_id=?",user,id);
                    for(String name:names) {
                        jdbc.update("insert into tag(id,user_id,name) values(?,?,?) on conflict(user_id,name) do nothing",UUID.randomUUID(),user,name);
                        jdbc.update("insert into card_tag(user_id,card_id,tag_id) select ?,?,id from tag where user_id=? and name=?",user,id,user,name);
                    }
                }
                return new MutationService.Change(obj("card",obj("id",id,"version",version+1),"detail",detail(user,id)),version+1,true);
            });
    }
    private String field(JsonNode p,String name,int max) { var v=p.path(name); if(!v.isTextual() || v.asText().length()>max) invalid(); return v.asText(); }
    private static String text(Object v) { return v==null?"":v.toString(); }
    private JsonNode json(Object v) { try { return mapper.readTree(v.toString()); } catch(IOException e) { throw new IllegalStateException(e); } }
    private static Map<String,Object> obj(Object... fields) { var m=new LinkedHashMap<String,Object>(); for(int i=0;i<fields.length;i+=2) m.put((String)fields[i],fields[i+1]); return m; }
    private static void invalid() { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
}
