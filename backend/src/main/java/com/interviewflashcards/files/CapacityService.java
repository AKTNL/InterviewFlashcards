package com.interviewflashcards.files;

import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CapacityService {
    private final JdbcTemplate jdbc;
    private final CapacityProperties limits;
    private final Path root;
    public CapacityService(JdbcTemplate jdbc, CapacityProperties limits, AppProperties properties) {
        this.jdbc=jdbc;this.limits=limits;this.root=Path.of(properties.storage().root()).toAbsolutePath();
    }
    private long used(UUID user) {
        long bytes=0;
        for(String table:List.of("source_document","asset","backup_job","restore_job"))
            for(UUID key:jdbc.queryForList("select storage_key from "+table+" where user_id=? and storage_key is not null",UUID.class,user)) {
                try { Path path=root.resolve(key.toString()); if(Files.isRegularFile(path)) bytes=Math.addExact(bytes,Files.size(path)); }
                catch(IOException e) { throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE); }
            }
        return bytes;
    }
    private long count(String sql, UUID user) { return jdbc.queryForObject(sql,Long.class,user); }
    public Map<String,Long> usage(UUID user) {
        return Map.of("usedBytes",used(user),"maxBytes",limits.userBytes(),"sources",count("select count(*) from source_document where user_id=?",user),
            "maxSources",(long)limits.sources(),"maxImports",(long)limits.imports(),"maxBackups",(long)limits.backups());
    }
    /** Caller holds the user write lock and a transaction. This row serializes capacity reservations. */
    public void check(UUID user, long bytes, boolean source, String task) {
        jdbc.queryForObject("select id from capacity_guard where id=1 for update",Integer.class);
        if(bytes<0 || bytes>limits.userBytes() || used(user)>limits.userBytes()-bytes) throw new ApiFailure(ErrorCode.QUOTA_EXCEEDED);
        if(source && count("select count(*) from source_document where user_id=?",user)>=limits.sources()) throw new ApiFailure(ErrorCode.QUOTA_EXCEEDED);
        if(task!=null) {
            String active=task.equals("import")?"select count(*) from import_job where user_id=? and status in ('QUEUED','PARSING')":
                "select (select count(*) from backup_job where user_id=? and status in ('QUEUED','RUNNING'))+(select count(*) from restore_job where user_id=? and status in ('QUEUED','VALIDATING','RESTORING','AWAITING_CONFIRMATION'))";
            long tasks=task.equals("import")?count(active,user):jdbc.queryForObject(active,Long.class,user,user);
            if(tasks>=(task.equals("import")?limits.imports():limits.backups())) throw new ApiFailure(ErrorCode.TASK_LIMITED);
            long global=jdbc.queryForObject("select (select count(*) from import_job where status in ('QUEUED','PARSING'))+(select count(*) from backup_job where status in ('QUEUED','RUNNING'))+(select count(*) from restore_job where status in ('QUEUED','VALIDATING','RESTORING','AWAITING_CONFIRMATION'))",Long.class);
            if(global>=limits.globalTasks()) throw new ApiFailure(ErrorCode.TASK_LIMITED);
        }
    }
    public void replacement(UUID user, com.fasterxml.jackson.databind.JsonNode data) {
        long bytes=0;for(String table:List.of("source_document","asset")) for(var row:data.path(table)) bytes=Math.addExact(bytes,row.path("byte_size").asLong());
        // Conservatively retain current files plus the prepared replacement until maintenance cleanup.
        check(user,bytes,false,null);
        if(data.path("source_document").size()>limits.sources()) throw new ApiFailure(ErrorCode.QUOTA_EXCEEDED);
    }
}
