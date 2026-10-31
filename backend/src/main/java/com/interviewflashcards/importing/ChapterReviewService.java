package com.interviewflashcards.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.interviewflashcards.importing.PdfEvidence.obj;

/** Chapter.version protects the whole editable aggregate, including check position and confirmation. */
@Service
public class ChapterReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ImportService imports;
    private final MutationService mutations;
    public ChapterReviewService(JdbcTemplate jdbc, ObjectMapper mapper, ImportService imports, MutationService mutations) {
        this.jdbc=jdbc; this.mapper=mapper; this.imports=imports; this.mutations=mutations;
    }
    private Map<String,Object> chapter(UUID user, UUID id) {
        var rows=jdbc.queryForList("select * from chapter where user_id=? and id=?",user,id);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> view(UUID user, UUID id) {
        var c=chapter(user,id);
        var items=jdbc.queryForList("select id,question,excluded,version,check_flags,parent_draft_id from draft_card where user_id=? and chapter_id=? order by sort_order,id",user,id)
            .stream().map(d -> obj("id",d.get("id"),"question",d.get("question"),"excluded",d.get("excluded"),"version",d.get("version"),"flags",json(d.get("check_flags")),"parentDraftId",d.get("parent_draft_id"))).toList();
        var progress=jdbc.queryForList("select current_draft_id from chapter_check_progress where user_id=? and chapter_id=?",user,id);
        var cards=jdbc.queryForList("select id,draft_id as \"draftId\" from card where user_id=? and chapter_id=? order by id",user,id);
        var history=jdbc.queryForList("select operation_type as action,input_ids,output_ids,reason,count_delta as \"countDelta\",created_at from draft_edit_operation where user_id=? and chapter_id=? order by created_at,id",user,id)
            .stream().map(r -> obj("action",r.get("action"),"inputIds",json(r.get("input_ids")),"outputIds",json(r.get("output_ids")),"reason",r.get("reason"),"countDelta",r.get("countDelta"),"createdAt",r.get("created_at").toString())).toList();
        return obj("id",id,"name",c.get("name"),"version",c.get("version"),"checkStatus",c.get("check_status"),"abandoned",c.get("abandoned"),
            "currentDraftId",progress.isEmpty()?null:progress.getFirst().get("current_draft_id"),"items",items,"cards",cards,"history",history,
            "activeCount",items.stream().filter(d -> !Boolean.TRUE.equals(d.get("excluded"))).count(),
            "flaggedCount",items.stream().filter(d -> !Boolean.TRUE.equals(d.get("excluded")) && !((JsonNode)d.get("flags")).isEmpty()).count(),
            "userCountDelta",history.stream().mapToInt(r -> ((Number)r.get("countDelta")).intValue()).sum());
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> cards(UUID user, UUID chapter) {
        chapter(user,chapter);
        return jdbc.queryForList("select c.*,d.source_entry,j.source_id,d.check_flags from card c join draft_card d on d.user_id=c.user_id and d.id=c.draft_id join import_job j on j.user_id=d.user_id and j.id=d.import_job_id where c.user_id=? and c.chapter_id=? order by d.sort_order,d.id",user,chapter)
            .stream().map(c -> obj("id",c.get("id"),"draftId",c.get("draft_id"),"sourceId",c.get("source_id"),"sourceEntry",c.get("source_entry"),"question",c.get("question"),"version",c.get("version"),"excluded",false,
                "originalContent",json(c.get("original_content")),"contentBlocks",json(c.get("display_content")),"flags",json(c.get("check_flags")),
                "sources",jdbc.queryForList("select physical_page,region from source_span where user_id=? and card_id=? order by sort_order",user,c.get("id")).stream().map(s -> obj("page",s.get("physical_page"),"bbox",json(s.get("region")))).toList(),
                "followups",jdbc.queryForList("select content from follow_up where user_id=? and card_id=? order by sort_order",user,c.get("id")).stream().map(f -> json(f.get("content"))).toList())).toList();
    }
    public JsonNode write(UUID user, UUID chapter, ChapterReviewController.Write command) throws IOException {
        return mutations.execute(user,new MutationService.Command(command.operationId(),command.dataEpoch()),"chapter.review",
            obj("id",chapter,"expectedVersion",command.expectedVersion(),"action",command.action(),"payload",command.payload()),()->{
                var c=chapter(user,chapter); var p=command.payload(); String action=command.action();
                // Repeat confirmation, even with a fresh operation ID, returns the existing stable mapping.
                if("CONFIRM".equals(action) && "IMPORTED".equals(c.get("check_status")))
                    return new MutationService.Change(Map.of("chapter",view(user,chapter)),((Number)c.get("version")).longValue(),false);
                if(((Number)c.get("version")).longValue()!=command.expectedVersion()) {
                    var details=obj("current",view(user,chapter));
                    if("EDIT".equals(action)) details.put("serverDraft",owned(user,chapter,id(p,"draftId")));
                    throw new ApiFailure(ErrorCode.VERSION_CONFLICT,details);
                }
                if(Boolean.TRUE.equals(c.get("abandoned"))) throw new ApiFailure(ErrorCode.CHAPTER_CLOSED);
                boolean imported="IMPORTED".equals(c.get("check_status"));
                if(imported && !List.of("EDIT","PROGRESS").contains(action)) throw new ApiFailure(ErrorCode.CHAPTER_CLOSED);
                String status=imported?"IMPORTED":"IN_PROGRESS";
                switch(action) {
                    case "EDIT" -> edit(user,chapter,p,imported);
                    case "MERGE" -> merge(user,chapter,p);
                    case "SPLIT" -> split(user,chapter,p);
                    case "EXCLUDE" -> {
                        var d=active(user,chapter,id(p,"draftId"));
                        jdbc.update("update draft_card set excluded=true,version=version+1 where user_id=? and id=?",user,d.get("id"));
                        audit(user,chapter,"EXCLUDE",List.of(d),List.of(),reason(p),-1);
                    }
                    case "PROGRESS" -> {
                        UUID current=id(p,"draftId"); owned(user,chapter,current);
                        jdbc.update("update chapter_check_progress set current_draft_id=? where user_id=? and chapter_id=?",current,user,chapter);
                    }
                    case "CONFIRM" -> { confirm(user,chapter,p); status="IMPORTED"; }
                    case "ABANDON" -> {
                        if(!p.path("confirmed").asBoolean()) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
                        var remaining=activeDrafts(user,chapter);
                        jdbc.update("update draft_card set excluded=true,version=version+1 where user_id=? and chapter_id=? and not excluded",user,chapter);
                        jdbc.update("update chapter set abandoned=true where user_id=? and id=?",user,chapter);
                        audit(user,chapter,"EXCLUDE",remaining,List.of(),"放弃本章剩余草稿："+reason(p),-remaining.size());
                    }
                    default -> throw new ApiFailure(ErrorCode.INVALID_REQUEST);
                }
                if(List.of("EDIT","SPLIT","EXCLUDE").contains(action))
                    jdbc.update("update chapter_check_progress set current_draft_id=? where user_id=? and chapter_id=?",id(p,"draftId"),user,chapter);
                if("MERGE".equals(action))
                    jdbc.update("update chapter_check_progress set current_draft_id=? where user_id=? and chapter_id=?",parseId(p.path("draftIds").get(0).asText()),user,chapter);
                jdbc.update("update chapter set check_status=?,version=version+1 where user_id=? and id=?",status,user,chapter);
                jdbc.update("update chapter_check_progress set status=?,version=version+1,updated_at=current_timestamp where user_id=? and chapter_id=?",status,user,chapter);
                return new MutationService.Change(Map.of("chapter",view(user,chapter)),command.expectedVersion()+1,true);
            });
    }
    private Map<String,Object> owned(UUID user,UUID chapter,UUID id) {
        var d=imports.draft(user,id);
        if(!chapter.equals(d.get("chapterId"))) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return d;
    }
    private Map<String,Object> active(UUID u,UUID c,UUID id) {
        var d=owned(u,c,id); if(Boolean.TRUE.equals(d.get("excluded"))) throw new ApiFailure(ErrorCode.CHAPTER_CLOSED); return d;
    }
    private List<Map<String,Object>> activeDrafts(UUID user,UUID chapter) {
        return jdbc.queryForList("select id from draft_card where user_id=? and chapter_id=? and not excluded order by sort_order,id",UUID.class,user,chapter)
            .stream().map(id -> owned(user,chapter,id)).toList();
    }
    private void edit(UUID user,UUID chapter,JsonNode p,boolean imported) {
        var d=active(user,chapter,id(p,"draftId")); var value=p.path("value"); validate(user,d,value);
        update(user,(UUID)d.get("id"),value);
        if(imported) syncCard(user,(UUID)d.get("id"));
        audit(user,chapter,"EDIT",List.of(d),List.of(d.get("id")),reason(p),0);
    }
    private void merge(UUID user,UUID chapter,JsonNode p) {
        var ids=p.path("draftIds"); if(!ids.isArray() || ids.size()<2 || ids.size()>50) invalid();
        var drafts=new ArrayList<Map<String,Object>>(); var unique=new HashSet<UUID>();
        for(var value:ids) { UUID id=parseId(value.asText()); if(!unique.add(id)) invalid(); drafts.add(active(user,chapter,id)); }
        UUID target=(UUID)drafts.getFirst().get("id");
        var blocks=mapper.createArrayNode(); var originals=mapper.createArrayNode(); var spans=mapper.createArrayNode(); var flags=mapper.createArrayNode(); var follows=mapper.createArrayNode();
        for(var d:drafts) { blocks.addAll((com.fasterxml.jackson.databind.node.ArrayNode)d.get("contentBlocks")); originals.addAll((com.fasterxml.jackson.databind.node.ArrayNode)d.get("originalContent"));
            spans.addAll((com.fasterxml.jackson.databind.node.ArrayNode)mapper.valueToTree(d.get("sources"))); flags.addAll((com.fasterxml.jackson.databind.node.ArrayNode)d.get("flags")); follows.addAll((com.fasterxml.jackson.databind.node.ArrayNode)mapper.valueToTree(d.get("followups"))); }
        var merged=mapper.valueToTree(obj("question",drafts.getFirst().get("question"),"contentBlocks",blocks,"sources",spans,"flags",flags,"followups",follows));
        update(user,target,merged);
        jdbc.update("update draft_card set original_content=?::jsonb where user_id=? and id=?",encode(originals),user,target);
        for(int i=1;i<drafts.size();i++) jdbc.update("update draft_card set excluded=true,version=version+1 where user_id=? and id=?",user,drafts.get(i).get("id"));
        audit(user,chapter,"MERGE",drafts,List.of(target),reason(p),1-drafts.size());
    }
    private void split(UUID user,UUID chapter,JsonNode p) {
        var d=active(user,chapter,id(p,"draftId")); var parts=p.path("parts");
        if(!parts.isArray() || parts.size()<2 || parts.size()>20) invalid();
        for(var part:parts) validate(user,d,part);
        var ids=new ArrayList<UUID>(); UUID original=(UUID)d.get("id"); ids.add(original);
        update(user,original,parts.get(0));
        for(int i=1;i<parts.size();i++) {
            UUID id=UUID.randomUUID(); ids.add(id);
            jdbc.update("""
                insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,original_content,source_metadata,sort_order,parent_draft_id)
                select ?,user_id,chapter_id,import_job_id,?,question,original_content,source_metadata,sort_order,? from draft_card where user_id=? and id=?
                """,id,"manual-"+id,original,user,original);
            update(user,id,parts.get(i));
        }
        audit(user,chapter,"SPLIT",List.of(d),ids,reason(p),parts.size()-1);
    }
    private void update(UUID user,UUID id,JsonNode value) {
        var blocks=value.path("contentBlocks").deepCopy();
        for(var block:blocks) ((com.fasterxml.jackson.databind.node.ObjectNode)block).put("type","MANUAL_TEXT");
        jdbc.update("update draft_card set question=?,content_blocks=?::jsonb,check_flags=?::jsonb,version=version+1 where user_id=? and id=?",
            value.path("question").asText().strip(),encode(blocks),encode(value.get("flags")),user,id);
        var d=imports.draft(user,id);
        jdbc.update("delete from source_span where user_id=? and draft_id=?",user,id);
        int order=0; for(var s:value.path("sources")) jdbc.update("insert into source_span(id,user_id,source_id,draft_id,physical_page,region,sort_order) values(?,?,?,?,?,?::jsonb,?)",
            UUID.randomUUID(),user,d.get("sourceId"),id,s.path("page").asInt(),encode(s.get("bbox")),order++);
        jdbc.update("delete from follow_up where user_id=? and draft_id=?",user,id);
        order=0; for(var f:value.path("followups")) jdbc.update("insert into follow_up(id,user_id,draft_id,content,sort_order) values(?,?,?,?::jsonb,?)",UUID.randomUUID(),user,id,encode(f),order++);
    }
    private void confirm(UUID user,UUID chapter,JsonNode p) {
        var drafts=activeDrafts(user,chapter);
        if(drafts.isEmpty()) throw new ApiFailure(ErrorCode.EMPTY_CHAPTER);
        long flagged=drafts.stream().filter(d -> !((JsonNode)d.get("flags")).isEmpty()).count();
        if(flagged>0 && !p.path("allowFlagged").asBoolean()) throw new ApiFailure(ErrorCode.CHECK_REQUIRED,Map.of("flaggedCount",flagged));
        for(var d:drafts) {
            UUID id=UUID.randomUUID();
            jdbc.update("insert into card(id,user_id,chapter_id,draft_id,question,original_content,display_content,needs_check) values(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                id,user,chapter,d.get("id"),d.get("question"),encode(d.get("originalContent")),encode(d.get("contentBlocks")),!((JsonNode)d.get("flags")).isEmpty());
            copyRelations(user,(UUID)d.get("id"),id);
        }
        jdbc.update("""
            update card c set parent_card_id=p.id from draft_card d,card p
            where c.user_id=? and c.chapter_id=? and d.user_id=c.user_id and d.id=c.draft_id
            and p.user_id=c.user_id and p.draft_id=d.parent_draft_id
            """,user,chapter);
    }
    private void syncCard(UUID user,UUID draft) {
        UUID card=jdbc.queryForObject("select id from card where user_id=? and draft_id=?",UUID.class,user,draft);
        jdbc.update("""
            update card c set question=d.question,display_content=d.content_blocks,needs_check=(d.check_flags<>'[]'::jsonb),version=c.version+1,display_revision=c.display_revision+1
            from draft_card d where c.user_id=? and c.id=? and d.user_id=c.user_id and d.id=c.draft_id
            """,user,card);
        jdbc.update("delete from source_span where user_id=? and card_id=?",user,card);
        jdbc.update("delete from follow_up where user_id=? and card_id=?",user,card);
        copyRelations(user,draft,card);
    }
    private void copyRelations(UUID user,UUID draft,UUID card) {
        jdbc.update("insert into source_span(id,user_id,source_id,card_id,asset_id,physical_page,region,sort_order) select gen_random_uuid(),user_id,source_id,?,asset_id,physical_page,region,sort_order from source_span where user_id=? and draft_id=?",card,user,draft);
        jdbc.update("insert into follow_up(id,user_id,card_id,content,sort_order) select gen_random_uuid(),user_id,?,content,sort_order from follow_up where user_id=? and draft_id=?",card,user,draft);
    }
    private void validate(UUID user,Map<String,Object> d,JsonNode v) {
        String question=v.path("question").asText("");
        if(question.isBlank() || question.length()>2000 || encode(v).length()>2_000_000) invalid();
        for(String field:List.of("contentBlocks","flags","sources","followups")) if(!v.path(field).isArray()) invalid();
        if(v.path("contentBlocks").isEmpty() || v.path("sources").isEmpty() || v.path("sources").size()>1000) invalid();
        int pages=jdbc.queryForObject("select page_count from source_document where user_id=? and id=?",Integer.class,user,d.get("sourceId"));
        var raw=jdbc.queryForObject("select raw_evidence::text from import_job where user_id=? and source_id=?",String.class,user,d.get("sourceId"));
        JsonNode evidence=raw==null?null:json(raw);
        for(var s:v.path("sources")) {
            int page=s.path("page").asInt(); var b=s.path("bbox");
            if(!s.path("page").isIntegralNumber() || page<1 || page>pages || !b.isArray() || b.size()!=4) invalid();
            for(var n:b) if(!n.isNumber() || !Double.isFinite(n.asDouble()) || n.asDouble()<0) invalid();
            if(b.get(2).asDouble()<=b.get(0).asDouble() || b.get(3).asDouble()<=b.get(1).asDouble()) invalid();
            if(evidence!=null) for(var ep:evidence.path("pages")) if(ep.path("page").asInt()==page &&
                (b.get(2).asDouble()>ep.path("width").asDouble(Double.MAX_VALUE)+0.01 || b.get(3).asDouble()>ep.path("height").asDouble(Double.MAX_VALUE)+0.01)) invalid();
        }
        for(var block:v.path("contentBlocks")) {
            if(!block.path("type").asText().matches("SOURCE_TEXT|MANUAL_TEXT") || !block.path("textSpans").isArray() || !block.path("images").isArray()) invalid();
            boolean matched=false; for(var s:v.path("sources")) if(s.path("page").asInt()==block.path("page").asInt()) matched=true;
            if(!matched) invalid();
            for(var span:block.path("textSpans")) if(!span.path("text").isTextual()) invalid();
        }
        for(var f:v.path("flags")) if(!f.path("kind").isTextual() || f.path("reason").asText().isBlank()) invalid();
        for(var f:v.path("followups")) if(f.path("title").asText().isBlank() || f.path("page").asInt()<1 || f.path("page").asInt()>pages) invalid();
    }
    private void audit(UUID user,UUID chapter,String action,List<Map<String,Object>> before,List<?> after,String reason,int delta) {
        jdbc.update("insert into draft_edit_operation(id,user_id,chapter_id,operation_type,input_ids,output_ids,reason,count_delta,evidence) values(?,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb)",
            UUID.randomUUID(),user,chapter,action,encode(before.stream().map(d -> d.get("id")).toList()),encode(after),reason,delta,encode(before));
    }
    private String reason(JsonNode p) { String r=p.path("reason").asText("").strip(); if(r.isEmpty() || r.length()>2000) invalid(); return r; }
    private UUID id(JsonNode p,String key) { return parseId(p.path(key).asText()); }
    private UUID parseId(String s) { try { return UUID.fromString(s); } catch(IllegalArgumentException e) { throw new ApiFailure(ErrorCode.INVALID_REQUEST); } }
    private void invalid() { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
    private JsonNode json(Object v) { try { return mapper.readTree(v.toString()); } catch(IOException e) { throw new IllegalStateException(e); } }
    private String encode(Object v) { return imports.encode(v); }
}
