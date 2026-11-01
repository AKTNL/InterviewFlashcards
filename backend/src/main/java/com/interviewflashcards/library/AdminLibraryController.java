package com.interviewflashcards.library;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.interviewflashcards.importing.*;
import com.interviewflashcards.sync.MutationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/library")
public record AdminLibraryController(LibraryService library,ApiResponses responses,SourceService sources,
        SourceController files,ImportService imports,ChapterReviewService review,JdbcTemplate jdbc) {
    private UUID epoch(UUID u){return jdbc.queryForObject("select data_epoch from app_user where id=?",UUID.class,u);}
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r) {return responses.success(library.adminList(a.id()),r);}
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal a,@Valid @RequestBody Create c,HttpServletRequest r) {
        return responses.success(library.create(a.id(),c.operationId(),c.name(),c.description()),r);
    }
    public record Create(@NotNull UUID operationId,@NotBlank @Size(max=200) String name,@NotNull @Size(max=4000) String description) {}
    @GetMapping("/{id}/preview") public ApiResponse<?> preview(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,HttpServletRequest r) throws IOException {return responses.success(library.preview(a.id(),id),r);}
    @PostMapping("/{id}/transition") public ApiResponse<?> transition(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@Valid @RequestBody LibraryController.Transition c,HttpServletRequest r) throws IOException {return responses.success(library.transition(a.id(),id,c),r);}
    @PostMapping(value="/{id}/sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@RequestParam UUID operationId,@RequestPart MultipartFile file,HttpServletRequest r) throws IOException {
        return responses.success(library.manage(a.id(),id,"UPLOAD",u->{
            // One PDF per library; its entire confirmed graph is the publishable collection.
            if(jdbc.queryForObject("select count(*) from source_document where user_id=?",Long.class,u)>0 && jdbc.queryForObject("select count(*) from mutation_receipt where user_id=? and operation_id=?",Long.class,u,operationId)==0)throw new ApiFailure(ErrorCode.IMPORT_CONTENT_EXISTS);
            return sources.upload(u,new MutationService.Command(operationId,epoch(u)),file);
        }),r);
    }
    @GetMapping("/{id}/jobs") public ApiResponse<?> jobs(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,null,u->imports.jobs(u,null)),r);}
    @GetMapping("/{id}/jobs/{job}/chapters") public ApiResponse<?> chapters(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID job,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,null,u->imports.chapters(u,job)),r);}
    @PostMapping("/{id}/jobs/{job}/retry") public ApiResponse<?> retry(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID job,@Valid @RequestBody Retry c,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,"RETRY",u->imports.retry(u,job,new MutationService.Command(c.operationId(),epoch(u)),c.expectedVersion())),r);}
    public record Retry(@NotNull UUID operationId,@PositiveOrZero long expectedVersion) {}
    @GetMapping("/{id}/chapters/{chapter}/review") public ApiResponse<?> review(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID chapter,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,null,u->review.view(u,chapter)),r);}
    @PostMapping("/{id}/chapters/{chapter}/review") public ApiResponse<?> write(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID chapter,@Valid @RequestBody Review c,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,"REVIEW",u->review.write(u,chapter,new ChapterReviewController.Write(c.operationId(),epoch(u),c.expectedVersion(),c.action(),c.payload()))),r);}
    public record Review(@NotNull UUID operationId,@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Pattern(regexp="EDIT|MERGE|SPLIT|EXCLUDE|PROGRESS|CONFIRM|ABANDON") String action,@NotNull com.fasterxml.jackson.databind.JsonNode payload) {}
    @GetMapping("/{id}/drafts/{draft}") public ApiResponse<?> draft(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID draft,HttpServletRequest r) throws IOException {return responses.success(library.manage(a.id(),id,null,u->imports.draft(u,draft)),r);}
    @GetMapping("/{id}/sources/{source}/content") public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> content(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID source) throws IOException {return library.manage(a.id(),id,null,u->files.content(new AccountPrincipal(u,"platform","!"),source));}
    @GetMapping("/{id}/sources/{source}/pages/{page}/image") public org.springframework.http.ResponseEntity<byte[]> image(@AuthenticationPrincipal AccountPrincipal a,@PathVariable UUID id,@PathVariable UUID source,@PathVariable int page) throws IOException {return library.manage(a.id(),id,null,u->files.pageImage(new AccountPrincipal(u,"platform","!"),source,page));}
}
