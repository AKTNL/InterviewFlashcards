package com.interviewflashcards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import com.interviewflashcards.files.SourceController.SourceView;
import com.interviewflashcards.files.SourceDocumentRepository;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncService {
    private final JdbcTemplate jdbc;
    private final SourceDocumentRepository sources;
    private final ObjectMapper mapper;
    public SyncService(JdbcTemplate jdbc, SourceDocumentRepository sources, ObjectMapper mapper) {
        this.jdbc = jdbc; this.sources = sources; this.mapper = mapper;
    }
    @Transactional
    public Map<String, Object> snapshot(UUID userId, boolean full) {
        // Hold a shared owner lock until all snapshot rows have been read.
        var owner = jdbc.queryForMap("select data_epoch, revision from app_user where id=? for share", userId);
        var result = new java.util.HashMap<String, Object>();
        result.put("dataEpoch", owner.get("data_epoch")); result.put("revision", owner.get("revision"));
        if (full) result.put("sources", sources.findAllByUserIdOrderByCreatedAtDesc(userId).stream().map(SourceView::of).toList());
        return result;
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
