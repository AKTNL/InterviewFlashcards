package com.interviewflashcards.library;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public record LibraryCleanup(LibraryService library) {
    @Scheduled(fixedDelayString="${app.library.cleanup-ms:3600000}",initialDelayString="${app.library.cleanup-ms:3600000}")
    public void cleanup(){library.cleanup();}
}
