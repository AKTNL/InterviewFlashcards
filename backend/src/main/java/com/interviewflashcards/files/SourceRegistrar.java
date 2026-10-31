package com.interviewflashcards.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SourceRegistrar {
    private final MutationService mutations;
    private final SourceDocumentRepository sources;
    private final FileStorage storage;
    private final Clock clock;
    public SourceRegistrar(MutationService mutations, SourceDocumentRepository sources, FileStorage storage, Clock clock) {
        this.mutations = mutations; this.sources = sources; this.storage = storage; this.clock = clock;
    }
    public JsonNode register(UUID userId, MutationService.Command command, String name, FileStorage.Staged staged, int pages) throws IOException {
        return mutations.execute(userId, command, "source.upload", java.util.Map.of("name", name, "sha256", staged.sha256(), "size", staged.size()), () -> {
            var existing = sources.findByUserIdAndSha256(userId, staged.sha256());
            if (existing.isPresent()) return new MutationService.Change(
                    new SourceController.UploadView(SourceController.SourceView.of(existing.get()), true), existing.get().getVersion(), false);
            var source = new SourceDocument(userId, name, staged, pages, clock.instant());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) storage.delete(source.getStorageKey());
                }
            });
            storage.publish(staged, source.getStorageKey());
            sources.saveAndFlush(source);
            return new MutationService.Change(new SourceController.UploadView(SourceController.SourceView.of(source), false), source.getVersion(), true);
        });
    }
}
