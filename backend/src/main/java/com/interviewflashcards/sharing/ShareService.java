package com.interviewflashcards.sharing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.interviewflashcards.sync.MutationService;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Content-only, server-created snapshots. No backup archives or client-supplied row graphs. */
@Service
public class ShareService {
    private static final List<String> TABLES=List.of("source_document","deck","module","chapter","import_job","draft_card","card","asset","source_span","follow_up","tag","card_tag");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MutationService mutations;
    private final FileStorage storage;
    private final CapacityService capacity;
    private final Clock clock;
    public ShareService(JdbcTemplate jdbc,ObjectMapper mapper,MutationService mutations,FileStorage storage,CapacityService capacity,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.mutations=mutations;this.storage=storage;this.capacity=capacity;this.clock=clock;
    }
    private JsonNode json(String text) {
        try { return mapper.readTree(text); } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private ArrayNode rows(String sql,Object... args) {
        var out=mapper.createArrayNode();
        jdbc.query("select (to_jsonb(t)-'user_id')::text from ("+sql+") t",(org.springframework.jdbc.core.RowCallbackHandler)r->out.add(json(r.getString(1))),args);
        return out;
    }
    private ObjectNode capture(UUID user,UUID deck) {
        var data=mapper.createObjectNode();
        data.set("deck",rows("select id,name,version,copy_origin from deck where user_id=? and id=?",user,deck));
        if(data.path("deck").isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        String chapters="select ch.id from chapter ch join module m on m.id=ch.module_id where ch.user_id=? and m.deck_id=? and ch.check_status='IMPORTED'";
        String cards="select id from card where user_id=? and chapter_id in ("+chapters+")";
        data.set("chapter",rows("select id,module_id,name,original_name,sort_order,check_status,version,abandoned from chapter where id in ("+chapters+") order by sort_order,id",user,deck));
        data.set("module",rows("select id,deck_id,name,original_name,sort_order from module where user_id=? and deck_id=? and id in (select module_id from chapter where id in ("+chapters+")) order by sort_order,id",user,deck,user,deck));
        data.set("card",rows("select id,chapter_id,draft_id,parent_card_id,question,original_content,display_content,needs_check,display_revision,version,applicable_version,technical_correction from card where id in ("+cards+") order by id",user,user,deck));
        if(data.path("card").isEmpty()) throw new ApiFailure(ErrorCode.SHARE_EMPTY);
        data.set("draft_card",rows("select d.id,d.chapter_id,d.import_job_id,d.source_entry,c.question,c.display_content as content_blocks,c.original_content,d.check_flags,d.sort_order from draft_card d join card c on c.draft_id=d.id where c.id in ("+cards+") order by d.id",user,user,deck));
        // Copy only job identity/source associations, never raw extraction, reports or unchecked drafts.
        data.set("import_job",rows("select id,source_id,deck_id from import_job where user_id=? and id in (select import_job_id from draft_card where id in (select draft_id from card where id in ("+cards+")))",user,user,user,deck));
        for(var node:data.withArray("import_job")) ((ObjectNode)node).put("status","READY").put("stage","SHARED_COPY").put("progress",100).put("parser_version","content-copy-v1");
        data.set("source_span",rows("select id,source_id,card_id,draft_id,asset_id,physical_page,region,sort_order from source_span where user_id=? and card_id in ("+cards+") order by sort_order,id",user,user,user,deck));
        data.set("follow_up",rows("select id,card_id,draft_id,content,sort_order from follow_up where user_id=? and card_id in ("+cards+") order by sort_order,id",user,user,user,deck));
        var sourceIds=new HashSet<String>();
        data.path("import_job").forEach(r->sourceIds.add(r.path("source_id").asText()));
        data.path("source_span").forEach(r->sourceIds.add(r.path("source_id").asText()));
        var sources=rows("select id,original_filename,sha256,page_count,byte_size,storage_key,version,shared_copy from source_document where user_id=? order by id",user);
        var selected=data.putArray("source_document");sources.forEach(r->{if(sourceIds.contains(r.path("id").asText()))selected.add(r);});
        var assets=rows("select id,source_id,storage_key,media_type,byte_size,sha256 from asset where user_id=? order by id",user);
        var chosenAssets=data.putArray("asset");assets.forEach(r->{if(sourceIds.contains(r.path("source_id").asText()))chosenAssets.add(r);});
        data.set("card_tag",rows("select card_id,tag_id from card_tag where user_id=? and card_id in ("+cards+")",user,user,user,deck));
        data.set("tag",rows("select id,name from tag where user_id=? and id in (select tag_id from card_tag where user_id=? and card_id in ("+cards+")) order by id",user,user,user,user,deck));
        return data;
    }
    private ObjectNode manifest(ObjectNode data,Map<String,Object> owner) {
        var m=mapper.createObjectNode().put("formatVersion",1).put("name",data.at("/deck/0/name").asText())
            .put("sender",Objects.toString(owner.get("nickname"),owner.get("username").toString()))
            .put("sourceRevision",((Number)owner.get("revision")).longValue()).put("capturedAt",clock.instant().toString());
        var counts=m.putObject("counts");for(String t:TABLES)counts.put(t,data.path(t).size());
        var sources=m.putArray("sources");for(var s:data.path("source_document")) sources.addObject()
            .put("name",s.path("original_filename").asText()).put("pageCount",s.path("page_count").asInt())
            .put("includedPages","1–"+s.path("page_count").asInt()).put("sha256",s.path("sha256").asText());
        var chapters=m.putArray("chapters");for(var c:data.path("chapter"))chapters.add(c.path("name"));
        var versions=m.putArray("contentVersions");for(var c:data.path("card"))versions.addObject().put("sourceCardId",c.path("id").asText()).put("version",c.path("version").asLong()).put("displayRevision",c.path("display_revision").asLong());
        m.put("contentHash",hash(encode(data).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return m;
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    @Transactional public ObjectNode preview(UUID user,UUID deck) {
        var owner=jdbc.queryForMap("select data_epoch,revision,username,nickname from app_user where id=? for share",user);
        var m=manifest(capture(user,deck),owner);
        m.put("dataEpoch",owner.get("data_epoch").toString());m.put("revision",((Number)owner.get("revision")).longValue());return m;
    }
    public JsonNode create(UUID user,ShareController.Create c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"share.create",c,()->{
            if(!c.rightsConfirmed() || c.expiresInHours()<1 || c.expiresInHours()>168)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var owner=jdbc.queryForMap("select data_epoch,revision,username,nickname from app_user where id=?",user);
            if(!c.expectedRevision().equals(owner.get("revision")))throw new ApiFailure(ErrorCode.DATA_PREVIEW_STALE);
            var recipients=jdbc.queryForList("select id from app_user where username=? and id<>? and password_hash<>'!'",c.recipient(),user);
            if(recipients.isEmpty())throw new ApiFailure(ErrorCode.SHARE_RECIPIENT_INVALID);
            if(jdbc.queryForObject("select count(*) from deck_share where user_id=? and not revoked and claim_count=0",Long.class,user)>=20)throw new ApiFailure(ErrorCode.TASK_LIMITED);
            UUID id=UUID.randomUUID();var data=capture(user,c.deckId());var m=manifest(data,owner);
            if(encode(data).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw new ApiFailure(ErrorCode.QUOTA_EXCEEDED);
            capacity.check(user,bytes(data),false,null);
            m.put("snapshotId",id.toString());
            jdbc.update("insert into deck_share(id,user_id,recipient_id,owner_epoch,manifest,snapshot,expires_at) values(?,?,?,?,?::jsonb,'{}',?)",id,user,recipients.getFirst().get("id"),c.dataEpoch(),encode(m),java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(c.expiresInHours()))));
            for(String t:List.of("source_document","asset")) for(var n:data.withArray(t)) {
                var row=(ObjectNode)n;UUID key=copyFile(row);row.put("storage_key",key.toString());
                jdbc.update("insert into share_resource(id,user_id,share_id,storage_key,byte_size) values(?,?,?,?,?)",UUID.randomUUID(),user,id,key,row.path("byte_size").asLong());
            }
            jdbc.update("update deck_share set snapshot=?::jsonb where id=?",encode(data),id);
            return new MutationService.Change(Map.of("share",invitation(user,id)),0,true);
        });
    }
    private long bytes(JsonNode data) {
        long total=0;for(String t:List.of("source_document","asset"))for(var r:data.path(t))total=Math.addExact(total,r.path("byte_size").asLong());return total;
    }
    /** Files are published before rows; failed DB commits remove every newly published key. */
    private UUID copyFile(JsonNode row) throws IOException {
        Path path=storage.read(UUID.fromString(row.path("storage_key").asText())).getFile().toPath();
        String digest;try(var input=Files.newInputStream(path)) {
            try { var md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)md.update(buffer,0,n);digest=HexFormat.of().formatHex(md.digest()); }
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        if(Files.size(path)!=row.path("byte_size").asLong() || !digest.equals(row.path("sha256").asText()))throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE);
        UUID key=UUID.randomUUID();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.delete(key);}
        });
        storage.publish(new FileStorage.Staged(path,digest,Files.size(path)),key);return key;
    }
    public Map<String,Object> list(UUID user) {
        return Map.of("outgoing",jdbc.queryForList("select id from deck_share where user_id=? order by created_at desc limit 100",UUID.class,user).stream().map(id->invitation(user,id)).toList(),
            "incoming",jdbc.queryForList("select id from deck_share where recipient_id=? order by created_at desc limit 100",UUID.class,user).stream().map(id->invitation(user,id)).toList());
    }
    public ObjectNode invitation(UUID user,UUID id) {
        var rows=jdbc.queryForList("select s.*,u.username as recipient from deck_share s join app_user u on u.id=s.recipient_id where s.id=? and (s.user_id=? or s.recipient_id=?)",id,user,user);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);var r=rows.getFirst();
        return mapper.createObjectNode().put("id",id.toString()).put("outgoing",user.equals(r.get("user_id")))
            .put("recipient",r.get("recipient").toString()).put("expiresAt",((java.sql.Timestamp)r.get("expires_at")).toInstant().toString())
            .put("revoked",(Boolean)r.get("revoked")).put("claimCount",((Number)r.get("claim_count")).intValue()).put("maxClaims",1)
            .put("expired",!clock.instant().isBefore(((java.sql.Timestamp)r.get("expires_at")).toInstant())).set("manifest",json(r.get("manifest").toString()));
    }
    @Transactional public ObjectNode revoke(UUID user,UUID id) {
        jdbc.queryForMap("select id from app_user where id=? for no key update",user);
        var rows=jdbc.queryForList("select id from deck_share where id=? and user_id=? for update",id,user);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        jdbc.update("update deck_share set revoked=true,snapshot='{}' where id=?",id);release(id);return invitation(user,id);
    }
    private void release(UUID id) {
        var keys=jdbc.queryForList("select storage_key from share_resource where share_id=?",UUID.class,id);
        jdbc.update("delete from share_resource where share_id=?",id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){keys.forEach(storage::delete);}
        });
    }
    /** Restoring a personal space never restores old sharing authorization. Caller holds owner lock. */
    @Transactional public void revokeAll(UUID user) {
        for(UUID id:jdbc.queryForList("select id from deck_share where user_id=? order by id for update",UUID.class,user)) {
            jdbc.update("update deck_share set revoked=true,snapshot='{}' where id=?",id);release(id);
        }
    }
    public JsonNode claim(UUID user,UUID id,ShareController.Claim c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"share.claim",Map.of("id",id,"command",c),()->{
            if(!c.confirmed())throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var rows=jdbc.queryForList("select * from deck_share where id=? and recipient_id=? for update",id,user);
            if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);var r=rows.getFirst();
            if((Boolean)r.get("revoked") || ((Number)r.get("claim_count")).intValue()!=0 || !clock.instant().isBefore(((java.sql.Timestamp)r.get("expires_at")).toInstant()))throw new ApiFailure(ErrorCode.SHARE_UNAVAILABLE);
            var original=(ObjectNode)json(r.get("snapshot").toString());
            capacity.addition(user,bytes(original),original.path("source_document").size());
            var ids=new HashMap<String,String>();for(String t:TABLES)for(var row:original.path(t))if(row.has("id"))ids.put(row.path("id").asText(),UUID.randomUUID().toString());
            var data=(ObjectNode)remap(original,ids);
            for(String t:List.of("source_document","asset"))for(int i=0;i<data.path(t).size();i++) {
                ((ObjectNode)data.path(t).get(i)).put("storage_key",copyFile(original.path(t).get(i)).toString());
                if(t.equals("source_document"))((ObjectNode)data.path(t).get(i)).put("shared_copy",true);
            }
            var origin=(ObjectNode)json(r.get("manifest").toString());origin.put("claimedAt",clock.instant().toString());
            ((ObjectNode)data.at("/deck/0")).set("copy_origin",origin);
            // Tags are personal names. Reuse existing recipient tags without importing any associations.
            for(var row:data.path("tag")) {
                var found=jdbc.queryForList("select id from tag where user_id=? and name=?",UUID.class,user,row.path("name").asText());
                if(!found.isEmpty()) {String old=row.path("id").asText();((ObjectNode)row).put("id",found.getFirst().toString());for(var link:data.path("card_tag"))if(link.path("tag_id").asText().equals(old))((ObjectNode)link).put("tag_id",found.getFirst().toString());}
            }
            insert(data,user);
            // Supporting draft relations mirror the received formal content for subsequent editing.
            jdbc.update("insert into source_span(id,user_id,source_id,draft_id,asset_id,physical_page,region,sort_order) select gen_random_uuid(),p.user_id,p.source_id,c.draft_id,p.asset_id,p.physical_page,p.region,p.sort_order from source_span p join card c on c.id=p.card_id join chapter ch on ch.id=c.chapter_id join module m on m.id=ch.module_id where p.user_id=? and m.deck_id=?",user,UUID.fromString(data.at("/deck/0/id").asText()));
            jdbc.update("insert into follow_up(id,user_id,draft_id,content,sort_order) select gen_random_uuid(),f.user_id,c.draft_id,f.content,f.sort_order from follow_up f join card c on c.id=f.card_id join chapter ch on ch.id=c.chapter_id join module m on m.id=ch.module_id where f.user_id=? and m.deck_id=?",user,UUID.fromString(data.at("/deck/0/id").asText()));
            validate(user);
            jdbc.update("update deck_share set claim_count=1,snapshot='{}' where id=?",id);release(id);
            return new MutationService.Change(Map.of("deckId",data.at("/deck/0/id").asText(),"origin",origin),0,true);
        });
    }
    private JsonNode remap(JsonNode n,Map<String,String> ids) {
        if(n.isTextual() && ids.containsKey(n.asText()))return TextNode.valueOf(ids.get(n.asText()));
        if(n.isArray()){var a=mapper.createArrayNode();n.forEach(v->a.add(remap(v,ids)));return a;}
        if(n.isObject()) {var o=mapper.createObjectNode();var text=Set.of("question","text","title","name","original_name","original_filename","source_entry","technical_correction","applicable_version","copy_origin");n.properties().forEach(e->o.set(e.getKey(),text.contains(e.getKey())?e.getValue():remap(e.getValue(),ids)));return o;}
        return n;
    }
    private void insert(ObjectNode data,UUID user) {
        for(String t:TABLES) {
            var rows=data.withArray(t);for(var r:rows)((ObjectNode)r).put("user_id",user.toString());
            if(rows.isEmpty())continue;
            // Snapshot fields are generated exclusively by capture(), never accepted from the client.
            var fields=new ArrayList<String>();rows.get(0).fieldNames().forEachRemaining(fields::add);String columns=String.join(",",fields);
            jdbc.update("insert into "+t+" ("+columns+") select "+columns+" from jsonb_populate_recordset(null::"+t+",?::jsonb)"+(t.equals("tag")?" on conflict(user_id,name) do nothing":""),encode(rows));
        }
    }
    private void validate(UUID user) {
        if(jdbc.queryForObject("select count(*) from source_span p join source_document s on s.id=p.source_id left join asset a on a.id=p.asset_id where p.user_id=? and (p.physical_page>s.page_count or (a.id is not null and a.source_id<>p.source_id))",Long.class,user)>0)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
    }
}
