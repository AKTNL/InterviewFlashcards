package com.interviewflashcards.files;

import com.interviewflashcards.auth.AccountRepository;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SourceRegistrar {
    private final AccountRepository accounts;
    private final SourceDocumentRepository sources;
    private final FileStorage storage;
    private final Clock clock;
    public SourceRegistrar(AccountRepository accounts, SourceDocumentRepository sources, FileStorage storage, Clock clock) {
        this.accounts = accounts; this.sources = sources; this.storage = storage; this.clock = clock;
    }
    @Transactional(rollbackFor = IOException.class)
    public Registration register(UUID userId, String name, FileStorage.Staged staged, int pages) throws IOException {
        // Serialize uploads per owner so simultaneous duplicate uploads return the same source.
        accounts.lockById(userId).orElseThrow(() -> new ApiFailure(ErrorCode.UNAUTHENTICATED));
        var existing = sources.findByUserIdAndSha256(userId, staged.sha256());
        if (existing.isPresent()) return new Registration(existing.get(), true);
        var source = new SourceDocument(userId, name, staged, pages, clock.instant());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.delete(source.getStorageKey());
            }
        });
        storage.publish(staged, source.getStorageKey());
        sources.saveAndFlush(source);
        return new Registration(source, false);
    }
    public record Registration(SourceDocument source, boolean duplicate) {}
}
