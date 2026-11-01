package com.interviewflashcards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every business write must enter here before reading or changing business rows.
 * The owner lock also provides a future restore barrier. No network work in callbacks. */
@Service
public class MutationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public MutationService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }
    public record Command(UUID operationId, UUID dataEpoch) {}
    public record Change(Object result, long entityVersion, boolean changed) {}
    @FunctionalInterface public interface Work { Change apply() throws IOException; }

    @Transactional(rollbackFor = Exception.class)
    public JsonNode execute(UUID userId, Command command, String kind, Object payload, Work work) throws IOException {
        if (command == null || command.operationId() == null || command.dataEpoch() == null)
            throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        // Serialize business writes/restores while permitting FK KEY SHARE checks from another
        // account's share invitation. Account IDs never change; FOR UPDATE would deadlock peers.
        var owner = jdbc.queryForMap("select data_epoch, revision from app_user where id=? for no key update", userId);
        if (!command.dataEpoch().equals(owner.get("data_epoch")))
            throw new ApiFailure(ErrorCode.EPOCH_EXPIRED, Map.of("dataEpoch", owner.get("data_epoch"), "revision", owner.get("revision")));
        String digest = digest(Map.of("kind", kind, "payload", payload));
        var receipts = jdbc.queryForList("select request_digest, result::text from mutation_receipt where user_id=? and data_epoch=? and operation_id=?",
                userId, command.dataEpoch(), command.operationId());
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (!digest.equals(receipt.get("request_digest"))) throw new ApiFailure(ErrorCode.OPERATION_ID_REUSED);
            return mapper.readTree((String) receipt.get("result"));
        }
        var change = work.apply();
        long revision = ((Number) owner.get("revision")).longValue() + (change.changed() ? 1 : 0);
        if (change.changed()) jdbc.update("update app_user set revision=? where id=?", revision, userId);
        ObjectNode result = mapper.valueToTree(change.result());
        result.set("mutation", mapper.valueToTree(Map.of("operationId", command.operationId(), "dataEpoch", command.dataEpoch(),
                "entityVersion", change.entityVersion(), "revision", revision, "serverTime", clock.instant())));
        jdbc.update("insert into mutation_receipt(id,user_id,data_epoch,operation_id,request_digest,result,revision) values(?,?,?,?,?,?::jsonb,?)",
                UUID.randomUUID(), userId, command.dataEpoch(), command.operationId(), digest, mapper.writeValueAsString(result), revision);
        return result;
    }

    String digest(Object value) throws IOException {
        try {
            String canonical = mapper.writeValueAsString(canonical(mapper.valueToTree(value)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Object canonical(JsonNode value) {
        if (value.isObject()) {
            var sorted = new TreeMap<String, Object>();
            value.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            var values = new ArrayList<Object>(); value.forEach(item -> values.add(canonical(item))); return values;
        }
        return value;
    }
}
