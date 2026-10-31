package com.interviewflashcards.files;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ApiResponse;
import com.interviewflashcards.common.web.ApiResponses;
import com.interviewflashcards.common.web.ErrorCode;
import com.interviewflashcards.sync.MutationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
                               FileStorage storage, ApiResponses responses, MutationService mutations) {
    @GetMapping("/api/sources")
    public ApiResponse<List<SourceView>> list(@AuthenticationPrincipal AccountPrincipal user, HttpServletRequest request) {
        return responses.success(sources.findAllByUserIdOrderByCreatedAtDesc(user.id()).stream().map(SourceView::of).toList(), request);
    }
    @PostMapping(value = "/api/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<JsonNode> upload(@AuthenticationPrincipal AccountPrincipal user, @RequestPart MultipartFile file,
                                          @RequestParam UUID operationId, @RequestParam UUID dataEpoch,
                                          HttpServletRequest request) {
        return responses.success(service.upload(user.id(), new MutationService.Command(operationId, dataEpoch), file), request);
    }
    @PatchMapping("/api/sources/{id}")
    public ApiResponse<JsonNode> rename(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id,
                                        @Valid @RequestBody Rename body, HttpServletRequest request) throws java.io.IOException {
        return responses.success(mutations.execute(user.id(), new MutationService.Command(body.operationId(), body.dataEpoch()),
                "source.rename", java.util.Map.of("id", id, "expectedVersion", body.expectedVersion(), "name", body.name()), () -> {
                    var source = service.owned(user.id(), id);
                    if (source.getVersion() != body.expectedVersion())
                        throw new ApiFailure(ErrorCode.VERSION_CONFLICT, java.util.Map.of("current", SourceView.of(source)));
                    source.rename(body.name());
                    // The explicit version advances even when saving the same name.
                    sources.flush();
                    return new MutationService.Change(java.util.Map.of("source", SourceView.of(source)), source.getVersion(), true);
                }), request);
    }
    public record Rename(@NotNull UUID operationId, @NotNull UUID dataEpoch, @NotNull @PositiveOrZero Long expectedVersion,
                         @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}/\\\\]+") String name) {}
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
    public record SourceView(UUID id, String originalFilename, String sha256, int pageCount, long byteSize, Instant createdAt, long version) {
        public static SourceView of(SourceDocument s) {
            return new SourceView(s.getId(), s.getOriginalFilename(), s.getSha256(), s.getPageCount(), s.getByteSize(), s.getCreatedAt(), s.getVersion());
        }
    }
    public record UploadView(SourceView source, boolean duplicate) {}
}
