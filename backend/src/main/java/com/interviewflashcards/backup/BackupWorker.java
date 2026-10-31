package com.interviewflashcards.backup;

import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.FileStorage;
import com.interviewflashcards.common.config.AppProperties;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable single-process jobs. Learning writes use a separate request thread and owner barrier. */
@Component
public class BackupWorker {
    private final JdbcTemplate jdbc;
    private final BackupService service;
    private final FileStorage storage;
    private final Path temporary;
    private volatile boolean ready;
    public BackupWorker(JdbcTemplate jdbc, BackupService service, FileStorage storage, AppProperties properties) { this.jdbc=jdbc;this.service=service;this.storage=storage;this.temporary=Path.of(properties.storage().temporaryRoot()).toAbsolutePath(); }
    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        try(var dirs=Files.list(temporary)) {
            dirs.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("(backup|restore|restore-check)-[0-9]+"))
                .forEach(BackupArchive::cleanup);
        } catch(java.io.IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Backup staging recovery cleanup failed"); }
        jdbc.update("update backup_job set status='FAILED',error_code='BACKUP_INTERRUPTED' where status='RUNNING'");
        jdbc.update("update restore_job set status='QUEUED',progress=0 where status='VALIDATING'");
        // A committed replacement sets READY in the same transaction. Any remaining RESTORING job
        // was interrupted before commit, and must be reviewed again rather than replayed on startup.
        jdbc.update("update restore_job set status='AWAITING_CONFIRMATION',error_code='RESTORE_INTERRUPTED',expected_epoch=null,expected_revision=null where status='RESTORING'");
        ready=true;
    }
    @Scheduled(fixedDelayString="${app.backup.poll-ms:1500}",initialDelayString="${app.backup.poll-ms:1500}")
    public synchronized void poll() {
        if(!ready) return;
        var restores=jdbc.queryForList("select id,user_id,status from restore_job where status in ('QUEUED','RESTORING') order by created_at limit 1");
        if(!restores.isEmpty()) {
            var row=restores.getFirst(); UUID user=(UUID)row.get("user_id"),id=(UUID)row.get("id");
            boolean restoring=row.get("status").equals("RESTORING");
            try {
                if(restoring) service.restore(user,id);
                else { jdbc.update("update restore_job set status='VALIDATING',progress=10 where id=?",id); service.validate(user,id); }
            } catch(Exception e) {
                boolean stale=e instanceof ApiFailure f && f.code()==ErrorCode.RESTORE_STALE;
                jdbc.update("update restore_job set status=?,error_code=?,expected_epoch=null,expected_revision=null,updated_at=current_timestamp where id=?",
                    stale?"AWAITING_CONFIRMATION":"FAILED",stale?"RESTORE_STALE":e instanceof BackupArchive.UnsupportedVersion?"BACKUP_VERSION_UNSUPPORTED":restoring?"RESTORE_FAILED":"BACKUP_INVALID",id);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Restore job failed id={} type={}",id,e.getClass().getSimpleName());
            }
            return;
        }
        var exports=jdbc.queryForList("select id,user_id from backup_job where status='QUEUED' order by created_at limit 1");
        if(!exports.isEmpty()) {
            var row=exports.getFirst(); UUID id=(UUID)row.get("id");
            try { jdbc.update("update backup_job set status='RUNNING',progress=10 where id=?",id); service.export((UUID)row.get("user_id"),id); }
            catch(Exception e) {
                jdbc.update("update backup_job set status='FAILED',error_code='BACKUP_FAILED',updated_at=current_timestamp where id=?",id);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Export job failed id={} type={}",id,e.getClass().getSimpleName());
            }
        }
    }
    @Scheduled(fixedDelayString="${app.backup.cleanup-ms:3600000}")
    public synchronized void cleanup() {
        if(!ready) return;
        for(var table:List.of("backup_job","restore_job")) {
            for(var row:jdbc.queryForList("select id,storage_key from "+table+" where storage_key is not null and updated_at<current_timestamp-interval '7 days' and status not in ('RUNNING','VALIDATING','RESTORING','QUEUED')")) {
                String status=table.equals("restore_job")?"case when status='READY' then 'READY' else 'FAILED' end":"'FAILED'";
                String error=table.equals("restore_job")?"case when status='READY' then error_code else 'BACKUP_EXPIRED' end":"'BACKUP_EXPIRED'";
                int changed=jdbc.update("update "+table+" set storage_key=null,status="+status+",error_code="+error+" where id=? and storage_key=? and updated_at<current_timestamp-interval '7 days' and status not in ('RUNNING','VALIDATING','RESTORING','QUEUED')",row.get("id"),row.get("storage_key"));
                if(changed==1) storage.delete((UUID)row.get("storage_key"));
            }
        }
    }
}
