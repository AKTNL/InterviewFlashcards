package com.interviewflashcards.library;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewflashcards.auth.Capabilities;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.FileStorage;
import com.interviewflashcards.sharing.ShareService;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class LibraryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShareService copies;
    private final MutationService mutations;
    private final FileStorage storage;
    private final Clock clock;
    private final TransactionTemplate tx;
    public LibraryService(JdbcTemplate jdbc,ObjectMapper mapper,ShareService copies,MutationService mutations,
                          FileStorage storage,Clock clock,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.mapper=mapper;this.copies=copies;this.mutations=mutations;this.storage=storage;this.clock=clock;
        tx=new TransactionTemplate(manager);
    }
    public void authorize(UUID actor) {
        var rows=jdbc.queryForList("select role from app_user where id=? and enabled and not must_change_password and space_kind='PERSONAL'",actor);
        if(rows.isEmpty() || !Capabilities.forRole((String)rows.getFirst().get("role")).contains(Capabilities.LIBRARY)) throw new ApiFailure(ErrorCode.FORBIDDEN);
    }
    private ObjectNode json(Object value) {
        try {return (ObjectNode)mapper.readTree(value.toString());} catch(IOException e){throw new IllegalStateException(e);}
    }
    private String encode(Object value) {
        try{return mapper.writeValueAsString(value);}catch(IOException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> row(UUID id,boolean lock) {
        var rows=jdbc.queryForList("select * from platform_library where id=?"+(lock?" for update":""),id);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private Map<String,Object> owner(UUID id) {
        return jdbc.queryForMap("select id,data_epoch,revision,username,nickname from app_user where id=? for no key update",id);
    }
    private void audit(UUID actor,UUID id,String action,Long version,String result) {
        jdbc.update("insert into platform_library_audit(id,actor_id,library_id,action,version,result) values(?,?,?,?,?,?)",UUID.randomUUID(),actor,id,action,version,result);
    }
    @FunctionalInterface public interface Work<T>{T run(UUID owner) throws IOException;}
    /** Every namespace read/write is fenced against deletion and parser commits. */
    public <T> T manage(UUID actor,UUID id,String action,Work<T> work) throws IOException {
        try {
            return tx.execute(status->{
                authorize(actor);var initial=row(id,false);UUID namespace=(UUID)initial.get("owner_id");owner(namespace);
                var current=row(id,true);
                if("DELETED".equals(current.get("status")))throw new ApiFailure(ErrorCode.NOT_FOUND);
                if(action!=null && "PUBLISHED".equals(current.get("status")))throw new ApiFailure(ErrorCode.LIBRARY_NOT_EDITABLE);
                try {
                    T result=work.run(namespace);
                    if(action!=null){jdbc.update("update platform_library set version=version+1 where id=?",id);audit(actor,id,action,((Number)current.get("version")).longValue()+1,"SUCCESS");}
                    return result;
                } catch(IOException e){throw new java.io.UncheckedIOException(e);}
            });
        } catch(RuntimeException e) {
            if(action!=null)tx.executeWithoutResult(s->audit(actor,id,action,null,e instanceof ApiFailure f?f.code().name():"FAILED"));
            if(e instanceof java.io.UncheckedIOException io)throw io.getCause();throw e;
        }
    }
    public Map<String,Object> create(UUID actor,UUID operation,String name,String description) {
        try {
            return tx.execute(status->{
                authorize(actor);
                // Serialize duplicate creation; namespace identities do not reserve human account capacity.
                jdbc.queryForMap("select id from app_user where id=? for no key update",actor);
                var existing=jdbc.queryForList("select * from platform_library where created_by=? and create_operation=?",actor,operation);
                if(!existing.isEmpty()) {
                    var r=existing.getFirst();if(!name.equals(r.get("name")) || !description.equals(r.get("description")))throw new ApiFailure(ErrorCode.OPERATION_ID_REUSED);
                    return adminView(r);
                }
                UUID id=UUID.randomUUID(),namespace=UUID.randomUUID();
                jdbc.update("insert into app_user(id,username,password_hash,nickname,enabled,space_kind) values(?,?,'!','平台知识库',false,'PLATFORM')",namespace,"@library:"+namespace);
                jdbc.update("insert into platform_library(id,owner_id,created_by,create_operation,name,description) values(?,?,?,?,?,?)",id,namespace,actor,operation,name,description);
                audit(actor,id,"CREATE",0L,"SUCCESS");return adminView(row(id,false));
            });
        } catch(RuntimeException e){tx.executeWithoutResult(s->audit(actor,null,"CREATE",null,e instanceof ApiFailure f?f.code().name():"FAILED"));throw e;}
    }
    public List<Map<String,Object>> adminList(UUID actor) {
        authorize(actor);return jdbc.queryForList("select * from platform_library order by created_at desc limit 200").stream().map(this::adminView).toList();
    }
    private Map<String,Object> adminView(Map<String,Object> r) {
        var v=new LinkedHashMap<String,Object>();
        for(String k:List.of("id","name","description","status","version","content_version","created_at","deleted_at","purged_at"))v.put(k,r.get(k));
        UUID namespace=(UUID)r.get("owner_id");
        v.put("revision",jdbc.queryForObject("select revision from app_user where id=?",Long.class,namespace));
        v.put("counts",Map.of("chapters",jdbc.queryForObject("select count(*) from chapter where user_id=?",Long.class,namespace),
            "cards",jdbc.queryForObject("select count(*) from card where user_id=?",Long.class,namespace),
            "activeTasks",jdbc.queryForObject("select count(*) from import_job where user_id=? and status in ('QUEUED','PARSING')",Long.class,namespace),
            "claims",jdbc.queryForObject("select count(*) from platform_library_claim where library_id=?",Long.class,r.get("id"))));
        v.put("manifest",json(r.get("manifest")));
        return v;
    }
    public Map<String,Object> preview(UUID actor,UUID id) throws IOException {
        return manage(actor,id,null,u->{var v=adminView(row(id,false));v.put("audit",jdbc.queryForList("select action,version,result,created_at from platform_library_audit where library_id=? order by created_at desc limit 30",id));return v;});
    }
    public JsonNode transition(UUID actor,UUID id,LibraryController.Transition c) throws IOException {
        try {
            authorize(actor);
            return mutations.execute(actor,new MutationService.Command(c.operationId(),c.dataEpoch()),"library.transition",Map.of("id",id,"command",c),()->{
                authorize(actor);var initial=row(id,false);UUID namespace=(UUID)initial.get("owner_id");var own=owner(namespace);var r=row(id,true);
                if(!c.confirmed())throw new ApiFailure(ErrorCode.INVALID_REQUEST);
                if(((Number)r.get("version")).longValue()!=c.expectedVersion() || ((Number)own.get("revision")).longValue()!=c.expectedRevision())throw new ApiFailure(ErrorCode.DATA_PREVIEW_STALE);
                if("DELETED".equals(r.get("status")))throw new ApiFailure(ErrorCode.NOT_FOUND);
                switch(c.action()) {
                    case "PUBLISH" -> {
                        if("PUBLISHED".equals(r.get("status")))throw new ApiFailure(ErrorCode.LIBRARY_NOT_EDITABLE);
                        if(!c.rightsConfirmed())throw new ApiFailure(ErrorCode.INVALID_REQUEST);
                        var jobs=jdbc.queryForList("select id,deck_id,status from import_job where user_id=?",namespace);
                        if(jobs.size()!=1 || !"READY".equals(jobs.getFirst().get("status")) || jdbc.queryForObject("select count(*) from chapter where user_id=? and check_status<>'IMPORTED' and not abandoned",Long.class,namespace)>0)throw new ApiFailure(ErrorCode.CHECK_REQUIRED);
                        UUID deck=(UUID)jobs.getFirst().get("deck_id");
                        jdbc.update("update deck set name=? where id=? and user_id=?",r.get("name"),deck,namespace);
                        var snapshot=copies.capture(namespace,deck);var manifest=copies.manifest(snapshot,own);
                        if(encode(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw new ApiFailure(ErrorCode.QUOTA_EXCEEDED);
                        // Check every resource before authorizing claims; never publish a broken snapshot.
                        for(String table:List.of("source_document","asset"))for(var resource:snapshot.path(table)) {
                            var path=storage.read(UUID.fromString(resource.path("storage_key").asText())).getFile().toPath();
                            if(java.nio.file.Files.size(path)!=resource.path("byte_size").asLong() || !com.interviewflashcards.backup.BackupArchive.hash(path).equals(resource.path("sha256").asText()))throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE);
                        }
                        long version=((Number)r.get("content_version")).longValue()+1;
                        manifest.put("libraryId",id.toString()).put("contentVersion",version).put("sender","平台知识库").put("snapshotId",UUID.randomUUID().toString());
                        jdbc.update("update platform_library set status='PUBLISHED',content_version=?,manifest=?::jsonb,snapshot=?::jsonb where id=?",version,encode(manifest),encode(snapshot),id);
                    }
                    case "UNLIST" -> jdbc.update("update platform_library set status='UNLISTED' where id=?",id);
                    case "DELETE" -> {
                        // Fence late worker commits; completed recipient copies use independent IDs/files.
                        jdbc.update("update import_job set status='FAILED',stage='LIBRARY_DELETED',attempt_token=null,error_code='LIBRARY_DELETED',error_message='平台知识库已删除',version=version+1 where user_id=? and status in ('QUEUED','PARSING')",namespace);
                        jdbc.update("update platform_library set status='DELETED',deleted_at=?,snapshot='{}' where id=?",java.sql.Timestamp.from(clock.instant()),id);
                    }
                    default -> throw new ApiFailure(ErrorCode.INVALID_REQUEST);
                }
                jdbc.update("update platform_library set version=version+1 where id=?",id);
                audit(actor,id,c.action(),c.expectedVersion()+1,"SUCCESS");return new MutationService.Change(Map.of("library",adminView(row(id,false))),c.expectedVersion()+1,false);
            });
        } catch(Exception e) {
            tx.executeWithoutResult(s->audit(actor,id,c.action(),null,e instanceof ApiFailure f?f.code().name():"FAILED"));throw e;
        }
    }
    public List<Map<String,Object>> catalog(int page,int size) {
        if(page<0 || page>100000 || size<1 || size>50)throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
        return jdbc.queryForList("select id,name,description,content_version,manifest::text,jsonb_build_object('card',jsonb_path_query_array(snapshot,'$.card[0 to 2]'))::text snapshot from platform_library where status='PUBLISHED' order by created_at desc,id limit ? offset ?",size,page*size).stream().map(r->{r.put("manifest",json(r.get("manifest")));
            var samples=new ArrayList<Map<String,String>>();
            var snapshot=json(r.remove("snapshot"));
            for(var card:snapshot.path("card")) {
                var answer=new StringBuilder();for(var block:card.path("display_content"))for(var span:block.path("textSpans"))answer.append(span.path("text").asText()).append("\n");
                samples.add(Map.of("question",card.path("question").asText(),"answer",answer.toString()));if(samples.size()==3)break;
            }
            r.put("samples",samples);return r;}).toList();
    }
    public List<Map<String,Object>> claims(UUID user) {
        return jdbc.queryForList("select library_id,deck_id,content_version,created_at from platform_library_claim where user_id=? order by created_at desc",user);
    }
    public JsonNode claim(UUID user,UUID id,LibraryController.Claim c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"library.claim",Map.of("id",id,"command",c),()->{
            if(!c.confirmed())throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var r=row(id,true);
            var previous=jdbc.queryForList("select deck_id from platform_library_claim where library_id=? and user_id=?",id,user);
            if(!previous.isEmpty())return new MutationService.Change(Map.of("deckId",previous.getFirst().get("deck_id"),"alreadyClaimed",true),0,false);
            if(!"PUBLISHED".equals(r.get("status")))throw new ApiFailure(ErrorCode.LIBRARY_UNAVAILABLE);
            if(c.contentVersion()!=((Number)r.get("content_version")).longValue())throw new ApiFailure(ErrorCode.DATA_PREVIEW_STALE);
            var received=copies.copyContent(user,json(r.get("snapshot")),json(r.get("manifest")));
            jdbc.update("insert into platform_library_claim(library_id,user_id,deck_id,content_version) values(?,?,?,?)",id,user,UUID.fromString(received.get("deckId").toString()),r.get("content_version"));
            return new MutationService.Change(received,0,true);
        });
    }
    /** Seven day retention. Removed namespaces cannot be read; retained rows support audit evidence. */
    public void cleanup() {
        for(UUID id:jdbc.queryForList("select id from platform_library where status='DELETED' and purged_at is null and deleted_at<?",UUID.class,java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(7))))) {
            var keys=tx.execute(s->{
                var initial=row(id,false);UUID namespace=(UUID)initial.get("owner_id");owner(namespace);var r=row(id,true);
                if(r.get("purged_at")!=null || jdbc.queryForObject("select count(*) from import_job where user_id=? and status in ('QUEUED','PARSING')",Long.class,namespace)>0)return null;
                var candidates=jdbc.queryForList("select storage_key from source_document where user_id=? union select storage_key from asset where user_id=?",UUID.class,namespace,namespace);
                for(UUID key:candidates) {
                    if(jdbc.queryForObject("select (select count(*) from source_document where storage_key=? and user_id<>?)+(select count(*) from asset where storage_key=? and user_id<>?)+(select count(*) from share_resource where storage_key=?)+(select count(*) from backup_job where storage_key=?)+(select count(*) from restore_job where storage_key=?)",Long.class,key,namespace,key,namespace,key,key,key)>0)return null;
                }
                return candidates;
            });
            if(keys==null)continue;
            // The reference check committed before physical deletion. A crash retries missing keys safely.
            boolean remaining=false;
            for(UUID key:keys)if(!storage.reclaim(key))remaining=true;
            if(!remaining)tx.executeWithoutResult(t->jdbc.update("update platform_library set purged_at=? where id=? and purged_at is null",java.sql.Timestamp.from(clock.instant()),id));
        }
    }
}
