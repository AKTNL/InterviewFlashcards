package com.interviewflashcards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import com.interviewflashcards.files.SourceController.SourceView;
import com.interviewflashcards.files.SourceDocumentRepository;
import com.interviewflashcards.common.time.LearningTime;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {
    static final List<String> TABLES = List.of("deck","module","chapter","draft_card","draft_edit_operation",
            "chapter_check_progress","card","asset","source_span","follow_up","tag","card_tag",
            "personal_annotation","review_policy","learning_preference","learning_preference_chapter","review_state",
            "review_event","learning_session","session_item","review_replay_allowance","import_job","sync_conflict");
    private final JdbcTemplate jdbc;
    private final SourceDocumentRepository sources;
    private final ObjectMapper mapper;
    private final LearningTime time;
    public SyncService(JdbcTemplate jdbc, SourceDocumentRepository sources, ObjectMapper mapper, LearningTime time) {
        this.jdbc = jdbc; this.sources = sources; this.mapper = mapper; this.time = time;
    }
    @Transactional
    public Map<String, Object> snapshot(UUID userId, boolean full) {
        // Hold a shared owner lock until all snapshot rows have been read.
        var owner = jdbc.queryForMap("select data_epoch, revision from app_user where id=? for share", userId);
        var result = new java.util.HashMap<String, Object>();
        result.put("dataEpoch", owner.get("data_epoch")); result.put("revision", owner.get("revision"));
        result.put("learningDate", time.today());
        if (full) {
            result.put("sources", sources.findAllByUserIdOrderByCreatedAtDesc(userId).stream().map(SourceView::of).toList());
            // An authoritative replacement snapshot: absence removes deleted entries, including after restore.
            // Files remain behind the authenticated source/asset endpoints; never expose storage keys.
            var data = new java.util.LinkedHashMap<String,Object>();
            for (String table : TABLES) {
                data.put(table, jdbc.query("select (to_jsonb(t)-'user_id'-'storage_key'-'raw_evidence'-'attempt_token')::text from " + table + " t where user_id=?",
                    (rs,n) -> {
                        try { return mapper.readTree(rs.getString(1)); }
                        catch (IOException e) { throw new IllegalStateException(e); }
                    }, userId));
            }
            result.put("data",data);
        }
        return result;
    }
    public record RowChange(String table, JsonNode key, Object row) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> changes(UUID userId, UUID epoch, long since) throws IOException {
        if (since < 0) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        // The cursor and every returned row describe one consistent committed state.
        var owner = jdbc.queryForMap("select data_epoch,revision from app_user where id=? for share",userId);
        long revision = ((Number)owner.get("revision")).longValue();
        var result = new LinkedHashMap<String,Object>();
        result.put("dataEpoch",owner.get("data_epoch")); result.put("revision",revision);
        result.put("learningDate",time.today()); result.put("fromRevision",since);
        String reason = !epoch.equals(owner.get("data_epoch")) ? "EPOCH_CHANGED"
                : since > revision ? "CURSOR_AHEAD" : revision-since > 1000 ? "TOO_MANY_REVISIONS" : null;
        if (reason == null && jdbc.queryForObject("select count(*) from sync_revision where user_id=? and data_epoch=? and revision>? and revision<=?",
                Long.class,userId,epoch,since,revision) != revision-since) reason="CURSOR_EXPIRED";
        if (reason != null) return fullRequired(result,reason);
        var identities = jdbc.queryForList("""
                select distinct table_name,row_key::text from sync_row_change
                where user_id=? and data_epoch=? and revision>? and revision<=?
                limit 5001
                """,userId,epoch,since,revision);
        if (identities.size()>5000) return fullRequired(result,"TOO_MANY_ROWS");
        var keys = new LinkedHashMap<String,List<JsonNode>>();
        for (var identity : identities) {
            String table=(String)identity.get("table_name");
            if (!TABLES.contains(table) && !table.equals("source_document")) return fullRequired(result,"SCHEMA_CHANGED");
            keys.computeIfAbsent(table,k->new ArrayList<>()).add(mapper.readTree((String)identity.get("row_key")));
        }
        var changes = new ArrayList<RowChange>();
        for (var entry : keys.entrySet()) {
            String table=entry.getKey();
            String join = switch(table) {
                case "card_tag" -> "t.card_id=(k.value->>'card_id')::uuid and t.tag_id=(k.value->>'tag_id')::uuid";
                case "learning_preference_chapter" -> "t.preference_id=(k.value->>'preference_id')::uuid and t.chapter_id=(k.value->>'chapter_id')::uuid";
                case "review_policy" -> "t.version=(k.value->>'version')::bigint";
                case "review_replay_allowance" -> "t.card_id=(k.value->>'card_id')::uuid and t.learning_date=(k.value->>'learning_date')::date";
                default -> "t.id=(k.value->>'id')::uuid";
            };
            changes.addAll(jdbc.query("select k.value::text,(to_jsonb(t)-'user_id'-'storage_key'-'raw_evidence'-'attempt_token')::text from jsonb_array_elements(?::jsonb) k left join "
                    +table+" t on t.user_id=? and "+join,(rs,n)->{
                try {
                    JsonNode row=rs.getString(2)==null?null:mapper.readTree(rs.getString(2));
                    Object publicRow=row;
                    if (row!=null && table.equals("source_document")) publicRow=new SourceView(UUID.fromString(row.get("id").asText()),
                            row.get("original_filename").asText(),row.get("sha256").asText(),row.get("page_count").asInt(),
                            row.get("byte_size").asLong(),java.time.Instant.parse(row.get("created_at").asText()),row.get("version").asLong());
                    return new RowChange(table,mapper.readTree(rs.getString(1)),publicRow);
                } catch(IOException e) { throw new IllegalStateException(e); }
            },mapper.writeValueAsString(entry.getValue()),userId));
        }
        result.put("fullRequired",false); result.put("changes",changes);
        return result;
    }
    private Map<String,Object> fullRequired(Map<String,Object> result,String reason) {
        result.put("fullRequired",true); result.put("reason",reason); return result;
    }
    @Transactional
    public JsonNode receipt(UUID userId, UUID epoch, UUID operationId) throws IOException {
        var owner = jdbc.queryForMap("select data_epoch, revision from app_user where id=? for share", userId);
        if (!epoch.equals(owner.get("data_epoch"))) throw new ApiFailure(ErrorCode.EPOCH_EXPIRED,
                Map.of("dataEpoch", owner.get("data_epoch"), "revision", owner.get("revision")));
        var rows = jdbc.queryForList("select result::text from mutation_receipt where user_id=? and data_epoch=? and operation_id=?", userId, epoch, operationId);
        if (rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return mapper.readTree((String) rows.getFirst().get("result"));
    }
}
