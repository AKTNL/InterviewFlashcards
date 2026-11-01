package com.interviewflashcards.backup;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.FileStorage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BackupService {
    // Dependency order also determines the reverse deletion order. Each table is inserted in one statement,
    // so self references (parent cards/drafts and compensating events) are checked at statement end.
    static final List<String> TABLES = List.of("source_document","deck","module","chapter","import_job","draft_card",
        "draft_edit_operation","chapter_check_progress","card","asset","source_span","follow_up","tag","card_tag",
        "personal_annotation","learning_preference","learning_preference_chapter","review_state","learning_session",
        "session_item","review_event","review_replay_allowance","sync_conflict");
    private final JdbcTemplate jdbc;
    private final com.interviewflashcards.files.CapacityService capacity;
    private final ObjectMapper mapper;
    private final FileStorage storage;
    private final Path temporary;
    private final TransactionTemplate tx;
    private final com.interviewflashcards.sharing.ShareService shares;
    public BackupService(JdbcTemplate jdbc, ObjectMapper mapper, FileStorage storage, AppProperties properties, PlatformTransactionManager manager, com.interviewflashcards.files.CapacityService capacity, com.interviewflashcards.sharing.ShareService shares) {
        this.shares=shares;
        this.capacity=capacity;
        this.jdbc=jdbc; this.mapper=mapper; this.storage=storage; temporary=Path.of(properties.storage().temporaryRoot()).toAbsolutePath();
        tx=new TransactionTemplate(manager);
    }
    private JsonNode json(String value) {
        try { return mapper.readTree(value); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public JsonNode createExport(UUID user, UUID operation) {
        return tx.execute(s -> {
            jdbc.queryForMap("select id from app_user where id=? for no key update",user);
            if (jdbc.queryForObject("select count(*) from backup_job where user_id=? and operation_id=?",Integer.class,user,operation)==0) capacity.check(user,0,false,"backup");
            jdbc.update("insert into backup_job(id,user_id,operation_id) values(?,?,?) on conflict(user_id,operation_id) do nothing",UUID.randomUUID(),user,operation);
            UUID id=jdbc.queryForObject("select id from backup_job where user_id=? and operation_id=?",UUID.class,user,operation);
            return job(user,id,false);
        });
    }
    public JsonNode upload(UUID user, UUID operation, MultipartFile file) throws IOException {
        try (var input=file.getInputStream(); var staged=storage.stage(input)) {
            return tx.execute(s -> {
                jdbc.queryForMap("select id from app_user where id=? for no key update",user);
                var existing=jdbc.queryForList("select id,request_digest from restore_job where user_id=? and operation_id=?",user,operation);
                if (!existing.isEmpty()) {
                    if (!staged.sha256().equals(existing.getFirst().get("request_digest"))) throw new ApiFailure(ErrorCode.OPERATION_ID_REUSED);
                    return job(user,(UUID)existing.getFirst().get("id"),true);
                }
                capacity.check(user,staged.size(),false,"backup");
                UUID id=UUID.randomUUID(), key=UUID.randomUUID();
                try { storage.publish(staged,key); } catch (IOException e) { throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE); }
                jdbc.update("insert into restore_job(id,user_id,operation_id,request_digest,storage_key) values(?,?,?,?,?)",id,user,operation,staged.sha256(),key);
                return job(user,id,true);
            });
        }
    }
    public List<JsonNode> jobs(UUID user, boolean restore) {
        return jdbc.query("select id from "+(restore?"restore_job":"backup_job")+" where user_id=? order by created_at desc limit 30",
            (rs,n)->job(user,rs.getObject(1,UUID.class),restore),user);
    }
    public JsonNode job(UUID user, UUID id, boolean restore) {
        var rows=jdbc.queryForList("select (to_jsonb(j)-'user_id'-'storage_key'-'request_digest')::text as value from "+(restore?"restore_job":"backup_job")+" j where user_id=? and id=?",user,id);
        if (rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return json((String)rows.getFirst().get("value"));
    }
    public org.springframework.core.io.Resource download(UUID user, UUID id) {
        var row=jdbc.queryForList("select storage_key from backup_job where user_id=? and id=? and status='READY'",user,id);
        if (row.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return storage.read((UUID)row.getFirst().get("storage_key"));
    }
    private ObjectNode counts(UUID user) {
        var counts=mapper.createObjectNode();
        for (var table:TABLES) counts.put(table,jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Long.class,user));
        return counts;
    }
    public JsonNode preview(UUID user, UUID id) {
        return tx.execute(s -> {
            var owner=jdbc.queryForMap("select data_epoch,revision from app_user where id=? for no key update",user);
            var row=job(user,id,true);
            if (!row.path("status").asText().equals("AWAITING_CONFIRMATION")) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var result=(ObjectNode)row.path("validation_result").deepCopy();
            result.set("currentCounts",counts(user));
            result.put("expectedEpoch",owner.get("data_epoch").toString()).put("expectedRevision",((Number)owner.get("revision")).longValue());
            jdbc.update("update restore_job set expected_epoch=?,expected_revision=?,validation_result=?::jsonb,error_code=null,updated_at=current_timestamp where user_id=? and id=?",
                owner.get("data_epoch"),owner.get("revision"),encode(result),user,id);
            return job(user,id,true);
        });
    }
    public JsonNode confirm(UUID user, UUID id, UUID epoch, long revision, boolean confirmed) {
        if (!confirmed) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        return tx.execute(s -> {
            var owner=jdbc.queryForMap("select data_epoch,revision from app_user where id=? for no key update",user);
            var row=jdbc.queryForList("select * from restore_job where user_id=? and id=? for update",user,id);
            if(row.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
            var r=row.getFirst(); String status=(String)r.get("status");
            // The restore job is the deduplication identity, including a lost confirmation response.
            if (status.equals("READY") || status.equals("RESTORING")) return job(user,id,true);
            if (!status.equals("AWAITING_CONFIRMATION")) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            if (!epoch.equals(owner.get("data_epoch")) || !epoch.equals(r.get("expected_epoch"))
                    || revision!=((Number)owner.get("revision")).longValue() || revision!=((Number)r.get("expected_revision")).longValue())
                throw new ApiFailure(ErrorCode.RESTORE_STALE);
            jdbc.update("update restore_job set status='RESTORING',progress=60,error_code=null,updated_at=current_timestamp where user_id=? and id=?",user,id);
            return job(user,id,true);
        });
    }
    /** Capture all rows and immutable file keys while holding the same lock as business writes. */
    public void export(UUID user, UUID id) throws IOException {
        storage.checkSpace(BackupArchive.MAX_EXPANDED+BackupArchive.MAX_ZIP);
        Path dir=Files.createTempDirectory(temporary,"backup-");
        try {
            var files=new LinkedHashMap<String,Path>();
            ObjectNode manifest=tx.execute(s -> {
                var owner=jdbc.queryForMap("select data_epoch,revision from app_user where id=? for share",user);
                var data=mapper.createObjectNode();
                for(var table:TABLES) {
                    var rows=data.putArray(table);
                    jdbc.query("select (to_jsonb(t)-'user_id'-'storage_key'-'attempt_token')::text from "+table+" t where user_id=?"+(table.equals("review_event")?" order by event_order":""),
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> rows.add(json(rs.getString(1))),user);
                }
                for(String table:List.of("source_document","asset")) for(var row:jdbc.queryForList("select id,storage_key from "+table+" where user_id=?",user)) {
                    var entity=find(data.withArray(table),row.get("id").toString());
                    try { files.put(fileName(table,entity),storage.read((UUID)row.get("storage_key")).getFile().toPath()); }
                    catch(IOException e) { throw new UncheckedIOException(e); }
                }
                Path dataFile=dir.resolve("data.json");
                try { mapper.writeValue(dataFile.toFile(),data); if(Files.size(dataFile)>BackupArchive.MAX_JSON) throw new IOException("Data limit"); }
                catch(IOException e){throw new java.io.UncheckedIOException(e);}
                files.put("data.json",dataFile);
                var m=mapper.createObjectNode().put("formatVersion",BackupArchive.VERSION).put("createdAt",Instant.now().toString())
                    .put("dataEpoch",owner.get("data_epoch").toString()).put("revision",((Number)owner.get("revision")).longValue());
                m.set("counts",counts(user));
                var versions=m.putArray("algorithmVersions");
                jdbc.queryForList("select distinct algorithm_version from review_event where user_id=? order by algorithm_version",String.class,user).forEach(versions::add);
                return m;
            });
            // Files are immutable. No business write is held up by hashing or ZIP compression.
            Path zip=dir.resolve("backup.zip"); BackupArchive.write(zip,manifest,files,mapper);
            // Validate our own output, including actual file lengths/hashes against source metadata.
            Path check=Files.createDirectory(dir.resolve("check")); var opened=BackupArchive.read(zip,check,mapper); validateStructure(opened);
            long zipSize=Files.size(zip); String zipHash=BackupArchive.hash(zip);
            tx.executeWithoutResult(s -> {
                jdbc.queryForMap("select id from app_user where id=? for no key update",user);
                capacity.check(user,zipSize,false,null);
                UUID key=UUID.randomUUID();
                try { storage.publish(new FileStorage.Staged(zip,zipHash,zipSize),key); }
                catch(IOException e) { throw new UncheckedIOException(e); }
                jdbc.update("update backup_job set status='READY',storage_key=?,format_version=1,manifest=?::jsonb,progress=100,updated_at=current_timestamp where user_id=? and id=?",key,encode(manifest),user,id);
            });
        } finally { BackupArchive.cleanup(dir); }
    }
    private static ObjectNode find(ArrayNode nodes, String id) {
        for(var row:nodes) if(row.path("id").asText().equals(id)) return (ObjectNode)row;
        throw new IllegalArgumentException("Missing entity");
    }
    private static String fileName(String table, JsonNode row) {
        return table.equals("source_document")?"sources/"+row.path("id").asText()+".pdf":
            "assets/"+row.path("id").asText()+(row.path("media_type").asText().equals("image/png")?".png":".jpg");
    }
    private BackupArchive.Opened open(UUID user, UUID id, Path dir) throws IOException {
        storage.checkSpace(BackupArchive.MAX_EXPANDED);
        UUID key=jdbc.queryForObject("select storage_key from restore_job where user_id=? and id=?",UUID.class,user,id);
        return BackupArchive.read(storage.read(key).getFile().toPath(),dir,mapper);
    }
    public void validate(UUID user, UUID id) throws IOException {
        Path dir=Files.createTempDirectory(temporary,"restore-check-");
        try {
            var opened=open(user,id,dir); validateStructure(opened);
            var data=prepare(opened,UUID.randomUUID(),false);
            // Exercise the real PostgreSQL constraints in an invisible, rolled-back validation owner.
            // This does not touch the current user's rows, locks, credentials or revision.
            tx.executeWithoutResult(s -> {
                UUID validationUser=UUID.randomUUID();
                jdbc.update("insert into app_user(id,username,password_hash) values(?,?,'!')",validationUser,"validation-"+validationUser);
                insert(data,validationUser); validateRelations(validationUser); s.setRollbackOnly();
            });
            var result=mapper.createObjectNode(); result.set("manifest",opened.manifest());
            result.put("scope","替换当前登录用户的全部学习资料、草稿、笔记、历史、偏好与会话；不替换账号与密码。");
            jdbc.update("update restore_job set status='AWAITING_CONFIRMATION',format_version=1,validation_result=?::jsonb,progress=50,updated_at=current_timestamp where user_id=? and id=?",encode(result),user,id);
            preview(user,id);
        } finally { BackupArchive.cleanup(dir); }
    }
    private void validateStructure(BackupArchive.Opened opened) throws IOException {
        var data=opened.data(); var names=new HashSet<String>(); data.fieldNames().forEachRemaining(names::add);
        if(!names.equals(new HashSet<>(TABLES))) throw new IOException("Unknown or missing tables");
        var fileNames=new HashSet<>(List.of("data.json","manifest.json")); var ids=new HashSet<String>(); long total=0;
        for(var table:TABLES) {
            if(!data.path(table).isArray() || !opened.manifest().path("counts").path(table).isIntegralNumber()
                    || opened.manifest().path("counts").path(table).asLong()!=data.path(table).size()) throw new IOException("Count mismatch");
            total+=data.path(table).size(); if(total>200000) throw new IOException("Row limit");
            var columns=new HashSet<>(columns(table)); columns.removeAll(Set.of("user_id","storage_key","attempt_token"));
            for(var row:data.path(table)) {
                if(!row.isObject()) throw new IOException("Invalid row");
                // Additive P1-09 metadata: continue accepting pre-V12 format-1 archives.
                if(table.equals("deck") && !row.has("copy_origin")) ((ObjectNode)row).putObject("copy_origin");
                if(table.equals("source_document") && !row.has("shared_copy")) ((ObjectNode)row).put("shared_copy",false);
                var keys=new HashSet<String>(); row.fieldNames().forEachRemaining(keys::add);
                // Incoming ownership is ignored; every inserted row gets the authenticated target.
                keys.remove("user_id");
                if(!keys.equals(columns)) throw new IOException("Invalid columns for "+table);
                if(row.has("id")) {
                    UUID.fromString(row.path("id").asText());
                    if(!ids.add(row.path("id").asText())) throw new IOException("Duplicate entity id");
                }
                if(table.equals("source_document") || table.equals("asset")) {
                    String name=fileName(table,row); Path file=opened.files().get(name);
                    if(file==null || Files.size(file)!=row.path("byte_size").asLong(-1) || !BackupArchive.hash(file).equals(row.path("sha256").asText())) throw new IOException("Resource mismatch");
                    fileNames.add(name);
                    if(table.equals("source_document")) {
                        try(var pdf=org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                            if(pdf.isEncrypted() || pdf.getNumberOfPages()!=row.path("page_count").asInt()) throw new IOException("Invalid PDF pages");
                        }
                    } else {
                        try(var image=javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
                            var readers=javax.imageio.ImageIO.getImageReaders(image);
                            if(!readers.hasNext()) throw new IOException("Invalid image");
                            var reader=readers.next(); try {
                                String format=reader.getFormatName();
                                if(!format.equalsIgnoreCase(row.path("media_type").asText().equals("image/png")?"png":"jpeg")) throw new IOException("Image type mismatch");
                            } finally { reader.dispose(); }
                        }
                    }
                }
            }
        }
        if(!fileNames.equals(opened.files().keySet())) throw new IOException("Unreferenced or missing resources");
    }
    private List<String> columns(String table) {
        return jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name=? order by ordinal_position",String.class,table);
    }
    private ObjectNode prepare(BackupArchive.Opened opened, UUID epoch, boolean publish) throws IOException {
        var ids=new HashMap<String,String>();
        for(var table:TABLES) for(var row:opened.data().path(table)) if(row.has("id")) ids.put(row.path("id").asText(),UUID.randomUUID().toString());
        var data=(ObjectNode)remap(opened.data(),ids);
        for(String table:List.of("source_document","asset")) {
            var original=opened.data().withArray(table); var rows=data.withArray(table);
            for(int i=0;i<rows.size();i++) {
                UUID key=UUID.randomUUID(); var row=(ObjectNode)rows.get(i); row.put("storage_key",key.toString());
                if(publish) {
                    Path file=opened.files().get(fileName(table,original.get(i)));
                    storage.publish(new FileStorage.Staged(file,row.path("sha256").asText(),Files.size(file)),key);
                }
            }
        }
        for(var node:data.withArray("import_job")) {
            var row=(ObjectNode)node; row.putNull("attempt_token");
            if(Set.of("PARSING","QUEUED").contains(row.path("status").asText())) {
                row.put("status","FAILED").put("stage","RESTORED_RETRY_REQUIRED").put("error_code","RESTORED_INTERRUPTED")
                    .put("error_message","备份时解析尚未完成，请检查后显式重试。");
            }
        }
        for(String table:List.of("review_event","sync_conflict")) for(var node:data.withArray(table)) ((ObjectNode)node).put("data_epoch",epoch.toString());
        return data;
    }
    private JsonNode remap(JsonNode node, Map<String,String> ids) {
        if(node.isTextual() && ids.containsKey(node.asText())) return TextNode.valueOf(ids.get(node.asText()));
        if(node.isObject()) {
            var copy=mapper.createObjectNode();
            var textFields=Set.of("notes","hint","memory_points","question","text","title","name","original_name","original_filename","source_entry","reason","technical_correction","applicable_version");
            node.properties().forEach(e->copy.set(e.getKey(),e.getKey().equals("copy_origin") || e.getValue().isTextual() && textFields.contains(e.getKey())?e.getValue():remap(e.getValue(),ids)));
            return copy;
        }
        if(node.isArray()) { var copy=mapper.createArrayNode(); node.forEach(e->copy.add(remap(e,ids))); return copy; }
        return node;
    }
    private void insert(ObjectNode data, UUID user) {
        for(String table:TABLES) {
            var rows=data.withArray(table);
            for(var row:rows) ((ObjectNode)row).put("user_id",user.toString());
            var columns=new ArrayList<>(columns(table)); columns.remove("event_order");
            String fields=String.join(",",columns);
            var jsonColumns=new HashSet<>(jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name=? and data_type='jsonb'",String.class,table));
            // jsonb_populate_record turns a JSON null into SQL NULL, but review events deliberately
            // store JSON null as a non-null before/after state. Read JSONB fields from the raw row.
            String selected=columns.stream().map(c->jsonColumns.contains(c)?Set.of("raw_evidence","region").contains(c)?"nullif(r.value->'"+c+"','null'::jsonb)":"r.value->'"+c+"'":"t."+c).collect(java.util.stream.Collectors.joining(","));
            jdbc.update("insert into "+table+" ("+fields+") select "+selected+" from jsonb_array_elements(?::jsonb) r(value) cross join lateral jsonb_populate_record(null::"+table+",r.value) t"
                +(table.equals("review_event")?" order by t.event_order":""),encode(rows));
        }
    }
    private void validateRelations(UUID user) {
        // Composite FKs/check constraints validate ownership and all structural links. These additional
        // checks cover ranges and semantic links not expressible by the existing foreign keys.
        for(String query:List.of(
            "select count(*) from source_span p join source_document s on s.id=p.source_id where p.user_id=? and p.physical_page>s.page_count",
            "select count(*) from source_span p join asset a on a.id=p.asset_id where p.user_id=? and p.source_id<>a.source_id",
            "select count(*) from draft_card d join chapter c on c.id=d.chapter_id join module m on m.id=c.module_id join import_job j on j.id=d.import_job_id where d.user_id=? and m.deck_id is distinct from j.deck_id",
            "select count(*) from review_event e join session_item i on i.id=e.session_item_id where e.user_id=? and e.card_id<>i.card_id",
            "select count(*) from review_event e join review_event p on p.id=e.undo_of where e.user_id=? and (e.card_id<>p.card_id or e.event_order<=p.event_order)",
            "select count(*) from review_state r join card c on c.id=r.card_id where r.user_id=? and (r.version<>c.review_version or r.first_learned_at>r.last_reviewed_at)",
            "select count(*) from review_state r where r.user_id=? and not exists(select 1 from review_event e where e.user_id=r.user_id and e.card_id=r.card_id)"
        )) if(jdbc.queryForObject(query,Long.class,user)>0) throw new IllegalArgumentException("Invalid backup relationships");
        for(var row:jdbc.queryForList("select before_state::text,after_state::text,rating,kind,algorithm_version from review_event where user_id=?",user)) {
            var before=json((String)row.get("before_state"));var after=json((String)row.get("after_state"));
            validateState(before);validateState(after);
            if((before.isNull() && after.isNull()) || (after.isNull() && !row.get("kind").equals("UNDO")) || row.get("algorithm_version").toString().isBlank()
                || !(after.isNull()?before:after).path("latestRating").asText().equals(row.get("rating"))) throw new IllegalArgumentException("Invalid review event");
        }
        for(var row:jdbc.queryForList("select d.content_blocks::text,d.original_content::text,s.page_count from draft_card d join import_job j on j.id=d.import_job_id join source_document s on s.id=j.source_id where d.user_id=?",user)) {
            validatePages(json((String)row.get("content_blocks")),((Number)row.get("page_count")).intValue());
            validatePages(json((String)row.get("original_content")),((Number)row.get("page_count")).intValue());
        }
        for(var row:jdbc.queryForList("select c.original_content::text,c.display_content::text,s.page_count from card c join draft_card d on d.id=c.draft_id join import_job j on j.id=d.import_job_id join source_document s on s.id=j.source_id where c.user_id=?",user)) {
            validatePages(json((String)row.get("original_content")),((Number)row.get("page_count")).intValue());
            validatePages(json((String)row.get("display_content")),((Number)row.get("page_count")).intValue());
        }
        // Compare instants as timestamps to accept equivalent UTC encodings and preserve undone states.
        var latest=jdbc.queryForList("select distinct on(e.card_id) e.after_state::text as state,r.* from review_event e left join review_state r on r.user_id=e.user_id and r.card_id=e.card_id where e.user_id=? order by e.card_id,e.event_order desc",user);
        for(var row:latest) {
            var state=json((String)row.get("state"));
            if(state.isNull()) { if(row.get("id")!=null) throw new IllegalArgumentException("Unexpected review state"); continue; }
            if(row.get("id")==null || !sameInstant(state.path("firstLearnedAt").asText(),row.get("first_learned_at"))
                || !sameInstant(state.path("lastReviewedAt").asText(),row.get("last_reviewed_at"))
                || !state.path("dueDate").asText().equals(row.get("due_date").toString()) || state.path("stage").asInt()!=((Number)row.get("stage")).intValue()
                || !state.path("latestRating").asText().equals(row.get("latest_rating")) || state.path("lapseCount").asInt()!=((Number)row.get("lapse_count")).intValue())
                throw new IllegalArgumentException("Review state/history mismatch");
        }
    }
    private void validateState(JsonNode state) {
        if(state.isNull()) return;
        var fields=new HashSet<String>();state.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("firstLearnedAt","lastReviewedAt","dueDate","stage","latestRating","lapseCount","version"))
            || !state.path("stage").isIntegralNumber() || state.path("stage").asInt() < -1 || state.path("stage").asInt()>4
            || !state.path("lapseCount").isIntegralNumber() || state.path("lapseCount").asLong()<0
            || !state.path("version").isIntegralNumber() || state.path("version").asLong()<0
            || !Set.of("FORGOT","VAGUE","REMEMBERED").contains(state.path("latestRating").asText())
            || Instant.parse(state.path("firstLearnedAt").asText()).isAfter(Instant.parse(state.path("lastReviewedAt").asText()))) throw new IllegalArgumentException("Invalid event state");
        LocalDate.parse(state.path("dueDate").asText());
    }
    private void validatePages(JsonNode content, int pages) {
        if(content.isObject()) {
            if(content.has("page") && (!content.path("page").isIntegralNumber() || content.path("page").asInt()<1 || content.path("page").asInt()>pages)) throw new IllegalArgumentException("Invalid content page");
            content.elements().forEachRemaining(n->validatePages(n,pages));
        } else if(content.isArray()) content.forEach(n->validatePages(n,pages));
    }
    private static boolean sameInstant(String json, Object timestamp) {
        // PostgreSQL timestamps have microsecond precision; event JSON can have nanoseconds.
        return Math.abs(Duration.between(Instant.parse(json),((java.sql.Timestamp)timestamp).toInstant()).toNanos())<=1000;
    }
    public void restore(UUID user, UUID id) throws IOException {
        Path dir=Files.createTempDirectory(temporary,"restore-");
        try {
            var opened=open(user,id,dir); validateStructure(opened);
            UUID epoch=UUID.randomUUID();
            // Prepare a fresh immutable namespace before the replacement transaction. Old files remain
            // available to in-flight readers and existing exports. Failed staging never clears data.
            tx.executeWithoutResult(s -> {
                var owner=jdbc.queryForMap("select data_epoch,revision from app_user where id=? for no key update",user);
                var job=jdbc.queryForMap("select * from restore_job where user_id=? and id=? for update",user,id);
                if(!job.get("status").equals("RESTORING")) return;
                if(!owner.get("data_epoch").equals(job.get("expected_epoch")) || !owner.get("revision").equals(job.get("expected_revision"))) throw new ApiFailure(ErrorCode.RESTORE_STALE);
                // Lock outgoing invitations before the capacity guard, matching claim's order.
                shares.revokeAll(user);
                capacity.replacement(user,opened.data());
                ObjectNode data;
                try { data=prepare(opened,epoch,true); } catch(IOException e) { throw new UncheckedIOException(e); }
                jdbc.update("delete from mutation_receipt where user_id=?",user);
                for(String table:TABLES.reversed()) jdbc.update("delete from "+table+" where user_id=?",user);
                insert(data,user); validateRelations(user);
                jdbc.update("update app_user set data_epoch=?,revision=revision+1 where id=?",epoch,user);
                var result=(ObjectNode)json(job.get("validation_result").toString());
                result.put("restoredEpoch",epoch.toString()).put("restoredRevision",((Number)owner.get("revision")).longValue()+1).put("completedAt",Instant.now().toString());
                jdbc.update("update restore_job set status='READY',progress=100,error_code=null,validation_result=?::jsonb,updated_at=current_timestamp where user_id=? and id=?",encode(result),user,id);
            });
        } finally { BackupArchive.cleanup(dir); }
    }
}
