package com.interviewflashcards.importing;

import static org.mockito.Mockito.*;
import com.interviewflashcards.files.FileStorage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ImportWorkerTest {
    @Test void failedStatusWriteIsRetriedAfterDatabaseConnectivityReturns() {
        var jdbc=mock(JdbcTemplate.class); var service=mock(ImportService.class); var files=mock(FileStorage.class);
        var worker=new ImportWorker(jdbc,service,files);
        var ticket=new ImportService.Ticket(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"test.pdf",null);
        when(files.read(ticket.storageKey())).thenThrow(new IllegalStateException("read failed"));
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(service).fail(eq(ticket),eq("IMPORT_FAILED"),anyString());
        worker.started(); worker.run(ticket); worker.poll(); worker.poll();
        verify(service,times(2)).fail(eq(ticket),eq("IMPORT_FAILED"),anyString());
        verify(files,times(1)).read(ticket.storageKey());
    }
}
