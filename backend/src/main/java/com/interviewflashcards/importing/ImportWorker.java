package com.interviewflashcards.importing;

import com.interviewflashcards.files.FileStorage;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/** Single-process worker. Heavy PDF work stays outside database transactions. */
@Component
@EnableScheduling
public class ImportWorker {
    private final JdbcTemplate jdbc;
    private final ImportService service;
    private final FileStorage storage;
    private volatile boolean ready;
    private record PendingFailure(ImportService.Ticket ticket,String code,String message) {}
    private PendingFailure pendingFailure;
    public ImportWorker(JdbcTemplate jdbc,ImportService service,FileStorage storage) { this.jdbc=jdbc;this.service=service;this.storage=storage; }
    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        for(var row:jdbc.queryForList("select id,user_id from import_job where status='PARSING'")) service.recover((UUID)row.get("id"),(UUID)row.get("user_id"));
        ready=true;
    }
    @Scheduled(fixedDelayString="${app.import.poll-ms:1500}",initialDelayString="${app.import.poll-ms:1500}")
    public void poll() {
        if(!ready) return;
        if(pendingFailure!=null) { flushFailure(); return; }
        var rows=jdbc.queryForList("select id,user_id from import_job where status='QUEUED' order by created_at limit 1");
        if(rows.isEmpty()) return;
        var row=rows.getFirst(); var ticket=service.claim((UUID)row.get("id"),(UUID)row.get("user_id"));
        if(ticket!=null) run(ticket);
    }
    public void run(ImportService.Ticket ticket) {
        try {
            var raw=ticket.raw();
            if(raw==null) {
                try(var doc=Loader.loadPDF(storage.read(ticket.storageKey()).getFile())) {
                    if(doc.isEncrypted() || doc.getNumberOfPages()<1) throw new PdfImportParser.ParseFailure("UNREADABLE_PDF","来源 PDF 不可读取，请重新选择文件。");
                    raw=PdfEvidence.extract(doc,page->{
                        if(page>0 && page%20==0 && !service.checkpoint(ticket,"EXTRACTING",1+(int)(60.0*page/doc.getNumberOfPages()),null)) throw new IllegalStateException("Import invalidated");
                    });
                }
                if(!service.checkpoint(ticket,"ANALYZING",65,raw)) return;
            }
            var parsed=new PdfImportParser().analyze(raw);
            if(!service.checkpoint(ticket,"PERSISTING",90,null)) return;
            service.complete(ticket,parsed);
        } catch(PdfImportParser.ParseFailure failure) { recordFailure(ticket,failure.code,failure.getMessage()); }
        catch(Exception failure) {
            LoggerFactory.getLogger(getClass()).warn("Import failed job={} type={}",ticket.id(),failure.getClass().getSimpleName());
            recordFailure(ticket,"IMPORT_FAILED","来源读取或草稿保存失败，已保留进度。可重试；文件不可用时请重新选择 PDF。");
        }
    }
    private void recordFailure(ImportService.Ticket ticket,String code,String message) {
        pendingFailure=new PendingFailure(ticket,code,message); flushFailure();
    }
    private void flushFailure() {
        var failure=pendingFailure;
        try { service.fail(failure.ticket(),failure.code(),failure.message()); pendingFailure=null; }
        catch(RuntimeException unavailable) {
            // A temporary database outage must not leave PARSING stuck after connectivity returns.
            LoggerFactory.getLogger(getClass()).warn("Import failure status pending job={}",failure.ticket().id());
        }
    }
}
