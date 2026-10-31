package com.interviewflashcards.files;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import com.interviewflashcards.common.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public record SourceController(SourceService service, SourceDocumentRepository sources, AssetRepository assets,
                               FileStorage storage, ApiResponses responses) {
    @GetMapping("/api/sources")
    public ApiResponse<List<SourceView>> list(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(sources.findAllByUserIdOrderByCreatedAtDesc(user.id()).stream().map(SourceView::of).toList(), request);
    }
    @PostMapping(value = "/api/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadView> upload(@AuthenticationPrincipal AccountPrincipal user, @RequestPart MultipartFile file,
                                          HttpServletRequest request) {
        var result = service.upload(user.id(), file);
        return responses.success(new UploadView(SourceView.of(result.source()), result.duplicate()), request);
    }
    @GetMapping("/api/sources/{id}")
    public ApiResponse<SourceView> detail(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id,
                                         HttpServletRequest request) {
        return responses.success(SourceView.of(service.owned(user.id(), id)), request);
    }
    @GetMapping("/api/sources/{id}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        var source = service.owned(user.id(), id);
        return file(storage.read(source.getStorageKey()), MediaType.APPLICATION_PDF, source.getOriginalFilename());
    }
    @GetMapping("/api/assets/{id}/content")
    public ResponseEntity<Resource> asset(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        var asset = assets.findByUserIdAndId(user.id(), id).orElseThrow(() -> new ApiFailure(ErrorCode.NOT_FOUND));
        return file(storage.read(asset.getStorageKey()), MediaType.parseMediaType(asset.getMediaType()), "image");
    }
    private static ResponseEntity<Resource> file(Resource resource, MediaType type, String name) {
        // Spring MVC handles Range/HEAD and streams Resource regions without buffering the whole file.
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
    public record SourceView(UUID id, String originalFilename, String sha256, int pageCount, long byteSize, Instant createdAt) {
        static SourceView of(SourceDocument s) {
            return new SourceView(s.getId(), s.getOriginalFilename(), s.getSha256(), s.getPageCount(), s.getByteSize(), s.getCreatedAt());
        }
    }
    public record UploadView(SourceView source, boolean duplicate) {}
}
