package com.interviewflashcards.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.interviewflashcards.importing.PdfEvidence.obj;

@Service
public class ImportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MutationService mutations;
    public ImportService(JdbcTemplate jdbc, ObjectMapper mapper, MutationService mutations) {
        this.jdbc=jdbc; this.mapper=mapper; this.mutations=mutations;
    }
    /** Called inside source.upload's owner lock and receipt transaction, including duplicates. */
    public UUID ensureJob(UUID user, UUID source) {
        var ids=jdbc.queryForList("select id from import_job where user_id=? and source_id=?",UUID.class,user,source);
        if (!ids.isEmpty()) return ids.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into import_job(id,user_id,source_id,stage,parser_version) values(?,?,?,'WAITING',?)",id,user,source,PdfImportParser.VERSION);
        return id;
    }
    private Map<String,Object> row(UUID user,UUID id) {
        var rows=jdbc.queryForList("select * from import_job where user_id=? and id=?",user,id);
        if (rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    public Map<String,Object> job(UUID user,UUID id) { return view(row(user,id)); }
    public List<Map<String,Object>> jobs(UUID user,UUID source) {
        return (source==null?jdbc.queryForList("select * from import_job where user_id=? order by created_at desc",user):
            jdbc.queryForList("select * from import_job where user_id=? and source_id=? order by created_at desc",user,source)).stream().map(this::view).toList();
    }
    private Map<String,Object> view(Map<String,Object> r) {
        return obj("id",r.get("id"),"sourceId",r.get("source_id"),"status",r.get("status"),"stage",r.get("stage"),"progress",r.get("progress"),
            "errorCode",r.get("error_code"),"errorMessage",r.get("error_message"),"parserVersion",r.get("parser_version"),
            "version",r.get("version"),"deckId",r.get("deck_id"),"report",json(r.get("report")),"updatedAt",r.get("updated_at").toString());
    }
    public JsonNode retry(UUID user,UUID id,MutationService.Command command,long version) throws IOException {
        return mutations.execute(user,command,"import.retry",Map.of("id",id,"expectedVersion",version),()->{
            var job=row(user,id);
            if (((Number)job.get("version")).longValue()!=version) throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",view(job)));
            if (!"FAILED".equals(job.get("status"))) throw new ApiFailure(ErrorCode.IMPORT_NOT_RETRYABLE);
            // A parser retry is never an editing or re-import API. Keep every existing draft/card intact.
            if (jdbc.queryForObject("select count(*) from draft_card where user_id=? and import_job_id=?",Integer.class,user,id)>0)
                throw new ApiFailure(ErrorCode.IMPORT_CONTENT_EXISTS);
            jdbc.update("update import_job set status='QUEUED',stage='WAITING',error_code=null,error_message=null,attempt_token=null,version=version+1,updated_at=current_timestamp where user_id=? and id=?",user,id);
            return new MutationService.Change(Map.of("job",job(user,id)),version+1,true);
        });
    }
    public List<Map<String,Object>> chapters(UUID user,UUID id) {
        row(user,id);
        return jdbc.queryForList("""
            select c.id,c.name,c.original_name as "originalName",c.check_status as "checkStatus",c.version,
            m.name as "module",c.sort_order as "sortOrder", count(d.id) as "candidateCount",
            count(d.id) filter(where d.check_flags <> '[]'::jsonb) as "flaggedCount"
            from chapter c join module m on m.user_id=c.user_id and m.id=c.module_id
            join import_job j on j.user_id=m.user_id and j.deck_id=m.deck_id
            left join draft_card d on d.user_id=c.user_id and d.chapter_id=c.id and d.import_job_id=j.id
            where j.user_id=? and j.id=? group by c.id,m.id order by m.sort_order,c.sort_order
            """,user,id);
    }
    public Map<String,Object> drafts(UUID user,UUID chapter,int offset,int limit) {
        if (jdbc.queryForObject("select count(*) from chapter where user_id=? and id=?",Integer.class,user,chapter)==0) throw new ApiFailure(ErrorCode.NOT_FOUND);
        var rows=jdbc.queryForList("select id,question,source_entry,check_flags,version,excluded,sort_order from draft_card where user_id=? and chapter_id=? order by sort_order,id limit ? offset ?",user,chapter,limit,offset);
        var items=rows.stream().map(r -> obj("id",r.get("id"),"question",r.get("question"),"sourceEntry",r.get("source_entry"),
            "flags",json(r.get("check_flags")),"version",r.get("version"),"excluded",r.get("excluded"))).toList();
        return obj("items",items,"offset",offset,"limit",limit,"total",jdbc.queryForObject("select count(*) from draft_card where user_id=? and chapter_id=?",Integer.class,user,chapter));
    }
    public Map<String,Object> draft(UUID user,UUID id) {
        var rows=jdbc.queryForList("select d.*,j.source_id from draft_card d join import_job j on j.user_id=d.user_id and j.id=d.import_job_id where d.user_id=? and d.id=?",user,id);
        if (rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        var r=rows.getFirst();
        var spans=jdbc.queryForList("select physical_page,region from source_span where user_id=? and draft_id=? order by sort_order",user,id)
            .stream().map(s -> obj("page",s.get("physical_page"),"bbox",json(s.get("region")))).toList();
        var followups=jdbc.queryForList("select content from follow_up where user_id=? and draft_id=? order by sort_order",user,id).stream().map(f -> json(f.get("content"))).toList();
        return obj("id",id,"chapterId",r.get("chapter_id"),"sourceId",r.get("source_id"),"question",r.get("question"),"sourceEntry",r.get("source_entry"),
            "contentBlocks",json(r.get("content_blocks")),"originalContent",json(r.get("original_content")),"metadata",json(r.get("source_metadata")),
            "flags",json(r.get("check_flags")),"sources",spans,"followups",followups,"version",r.get("version"),"excluded",r.get("excluded"));
    }
    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); } catch (IOException e) { throw new IllegalStateException("Invalid stored import JSON",e); }
    }
    String encode(Object value) { try { return mapper.writeValueAsString(value); } catch (IOException e) { throw new IllegalStateException(e); } }

    public record Ticket(UUID id,UUID user,UUID epoch,UUID token,UUID source,UUID storageKey,String filename,JsonNode raw) {}
    @Transactional
    public Ticket claim(UUID id,UUID user) {
        var owner=jdbc.queryForMap("select data_epoch from app_user where id=? for update",user);
        var job=row(user,id); if (!"QUEUED".equals(job.get("status"))) return null;
        UUID token=UUID.randomUUID();
        jdbc.update("update import_job set status='PARSING',stage=?,progress=?,attempt_token=?,parser_version=?,version=version+1,updated_at=current_timestamp where user_id=? and id=?",
            job.get("raw_evidence")==null?"EXTRACTING":"ANALYZING",job.get("raw_evidence")==null?1:65,token,PdfImportParser.VERSION,user,id);
        jdbc.update("update app_user set revision=revision+1 where id=?",user);
        var s=jdbc.queryForMap("select storage_key,original_filename from source_document where user_id=? and id=?",user,job.get("source_id"));
        return new Ticket(id,user,(UUID)owner.get("data_epoch"),token,(UUID)job.get("source_id"),(UUID)s.get("storage_key"),(String)s.get("original_filename"),job.get("raw_evidence")==null?null:json(job.get("raw_evidence")));
    }
    private boolean active(Ticket t) {
        var owner=jdbc.queryForMap("select data_epoch from app_user where id=? for update",t.user());
        if (!t.epoch().equals(owner.get("data_epoch"))) return false;
        var rows=jdbc.queryForList("select status,attempt_token from import_job where user_id=? and id=?",t.user(),t.id());
        return !rows.isEmpty() && "PARSING".equals(rows.getFirst().get("status")) && t.token().equals(rows.getFirst().get("attempt_token"));
    }
    @Transactional
    public boolean checkpoint(Ticket t,String stage,int progress,JsonNode raw) {
        if (!active(t)) return false;
        jdbc.update("update import_job set stage=?,progress=?,raw_evidence=coalesce(?::jsonb,raw_evidence),version=version+1,updated_at=current_timestamp where user_id=? and id=?",
            stage,progress,raw==null?null:encode(raw),t.user(),t.id());
        jdbc.update("update app_user set revision=revision+1 where id=?",t.user()); return true;
    }
    @Transactional
    public void fail(Ticket t,String code,String message) {
        if (!active(t)) return;
        jdbc.update("update import_job set status='FAILED',error_code=?,error_message=?,attempt_token=null,version=version+1,updated_at=current_timestamp where user_id=? and id=?",code,message,t.user(),t.id());
        jdbc.update("update app_user set revision=revision+1 where id=?",t.user());
    }
    @Transactional
    public void recover(UUID id,UUID user) {
        jdbc.queryForMap("select id from app_user where id=? for update",user);
        int changed=jdbc.update("update import_job set status='FAILED',error_code='INTERRUPTED',error_message='服务在解析期间重启，已保留阶段和提取证据，请重试继续。',attempt_token=null,version=version+1,updated_at=current_timestamp where user_id=? and id=? and status='PARSING'",user,id);
        if(changed>0) jdbc.update("update app_user set revision=revision+1 where id=?",user);
    }
    @Transactional
    public void complete(Ticket t,PdfImportParser.Result parsed) {
        if (!active(t)) return;
        if (jdbc.queryForObject("select count(*) from draft_card where user_id=? and import_job_id=?",Integer.class,t.user(),t.id())>0)
            throw new ApiFailure(ErrorCode.IMPORT_CONTENT_EXISTS);
        UUID deck=UUID.randomUUID(); jdbc.update("insert into deck(id,user_id,name) values(?,?,?)",deck,t.user(),t.filename());
        var modules=new LinkedHashMap<String,UUID>(); var chapters=new LinkedHashMap<String,UUID>();
        int order=0;
        for(var c:parsed.cards()) {
            UUID module=modules.get(c.module());
            if(module==null) { module=UUID.randomUUID(); modules.put(c.module(),module); jdbc.update("insert into module(id,user_id,deck_id,name,original_name,sort_order) values(?,?,?,?,?,?)",module,t.user(),deck,c.module(),c.module(),modules.size()-1); }
            UUID chapter=chapters.get(c.chapter());
            if(chapter==null) { chapter=UUID.randomUUID(); chapters.put(c.chapter(),chapter); jdbc.update("insert into chapter(id,user_id,module_id,name,original_name,sort_order) values(?,?,?,?,?,?)",chapter,t.user(),module,c.chapter(),c.originalChapter(),chapters.size()-1);
                jdbc.update("insert into chapter_check_progress(id,user_id,chapter_id) values(?,?,?)",UUID.randomUUID(),t.user(),chapter); }
            UUID draft=UUID.randomUUID(); var blocks=new ArrayList<Object>();
            for(var span:c.sources()) blocks.add(obj("type","SOURCE_TEXT","page",span.get("page"),"bbox",span.get("bbox"),"textSpans",span.get("textSpans"),"images",span.get("images")));
            jdbc.update("insert into draft_card(id,user_id,chapter_id,import_job_id,source_entry,question,content_blocks,original_content,check_flags,source_metadata,sort_order) values(?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)",
                draft,t.user(),chapter,t.id(),c.entry(),c.question(),encode(blocks),encode(blocks),encode(c.flags()),encode(c.metadata()),order++);
            int s=0; for(var span:c.sources()) jdbc.update("insert into source_span(id,user_id,source_id,draft_id,physical_page,region,sort_order) values(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),t.user(),t.source(),draft,span.get("page"),encode(span.get("bbox")),s++);
            s=0; for(var f:c.followups()) jdbc.update("insert into follow_up(id,user_id,draft_id,content,sort_order) values(?,?,?,?::jsonb,?)",UUID.randomUUID(),t.user(),draft,encode(f),s++);
        }
        jdbc.update("update import_job set status='READY',stage='COMPLETE',progress=100,deck_id=?,report=?::jsonb,error_code=null,error_message=null,attempt_token=null,version=version+1,updated_at=current_timestamp where user_id=? and id=?",deck,encode(parsed.report()),t.user(),t.id());
        jdbc.update("update app_user set revision=revision+1 where id=?",t.user());
    }
}
