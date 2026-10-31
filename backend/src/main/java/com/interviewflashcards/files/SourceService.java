package com.interviewflashcards.files;

import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public record SourceService(FileStorage storage, SourceRegistrar registrar, SourceDocumentRepository sources) {
    public SourceRegistrar.Registration upload(UUID userId, MultipartFile file) {
        String type = file.getContentType();
        if (type == null || !(type.equalsIgnoreCase("application/pdf") || type.equalsIgnoreCase("application/octet-stream"))
                || file.isEmpty()) throw new ApiFailure(ErrorCode.INVALID_PDF);
        String name = displayName(file.getOriginalFilename());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new ApiFailure(ErrorCode.INVALID_PDF);
        try (var input = file.getInputStream(); var staged = storage.stage(input)) {
            int pages;
            try (var header = java.nio.file.Files.newInputStream(staged.path())) {
                if (!new String(header.readNBytes(5), StandardCharsets.US_ASCII).equals("%PDF-")) {
                    throw new ApiFailure(ErrorCode.INVALID_PDF);
                }
            }
            try (var document = Loader.loadPDF(staged.path().toFile())) {
                pages = document.getNumberOfPages();
                if (document.isEncrypted() || pages < 1) throw new ApiFailure(ErrorCode.INVALID_PDF);
                // Force page tree traversal now; broken PDFs must not be registered as readable.
                for (var page : document.getPages()) page.getMediaBox();
            } catch (IOException | IllegalArgumentException exception) { throw new ApiFailure(ErrorCode.INVALID_PDF); }
            return registrar.register(userId, name, staged, pages);
        } catch (IOException exception) { throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE); }
    }
    public SourceDocument owned(UUID userId, UUID id) {
        return sources.findByUserIdAndId(userId, id).orElseThrow(() -> new ApiFailure(ErrorCode.NOT_FOUND));
    }
    private static String displayName(String raw) {
        if (raw == null) return "document.pdf";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) return "document.pdf";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
