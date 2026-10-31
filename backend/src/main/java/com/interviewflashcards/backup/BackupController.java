package com.interviewflashcards.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public record BackupController(BackupService service, ApiResponses responses) {
    public record Export(@NotNull UUID operationId) {}
    public record Confirm(@NotNull UUID dataEpoch, @NotNull @PositiveOrZero Long expectedRevision, boolean confirmed) {}
    @GetMapping("/api/backups")
    public ApiResponse<List<JsonNode>> exports(@AuthenticationPrincipal AccountPrincipal u,HttpServletRequest r) { return responses.success(service.jobs(u.id(),false),r); }
    @PostMapping("/api/backups")
    public ApiResponse<JsonNode> export(@AuthenticationPrincipal AccountPrincipal u,@Valid @RequestBody Export body,HttpServletRequest r) { return responses.success(service.createExport(u.id(),body.operationId()),r); }
    @GetMapping("/api/backups/{id}")
    public ApiResponse<JsonNode> exportJob(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) { return responses.success(service.job(u.id(),id,false),r); }
    @GetMapping("/api/backups/{id}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("flashcards-"+id+".zip").build().toString()).body(service.download(u.id(),id));
    }
    @GetMapping("/api/restores")
    public ApiResponse<List<JsonNode>> restores(@AuthenticationPrincipal AccountPrincipal u,HttpServletRequest r) { return responses.success(service.jobs(u.id(),true),r); }
    @PostMapping(value="/api/restores",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<JsonNode> upload(@AuthenticationPrincipal AccountPrincipal u,@RequestPart MultipartFile file,@RequestParam UUID operationId,HttpServletRequest r) throws IOException { return responses.success(service.upload(u.id(),operationId,file),r); }
    @GetMapping("/api/restores/{id}")
    public ApiResponse<JsonNode> restoreJob(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) { return responses.success(service.job(u.id(),id,true),r); }
    @PostMapping("/api/restores/{id}/preview")
    public ApiResponse<JsonNode> preview(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) { return responses.success(service.preview(u.id(),id),r); }
    @PostMapping("/api/restores/{id}/confirm")
    public ApiResponse<JsonNode> confirm(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@Valid @RequestBody Confirm body,HttpServletRequest r) { return responses.success(service.confirm(u.id(),id,body.dataEpoch(),body.expectedRevision(),body.confirmed()),r); }
}
