package com.interviewflashcards.admin;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public record AdminController(AdminService service,ApiResponses responses) {
    @GetMapping("/users") public ApiResponse<?> users(@AuthenticationPrincipal AccountPrincipal actor,@RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="ALL") String invitation,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){
        return responses.success(service.users(actor.id(),search,state,invitation,page,size),request);
    }
    @GetMapping("/users/{id}") public ApiResponse<?> detail(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,HttpServletRequest request){return responses.success(service.detail(actor.id(),id),request);}
    @GetMapping("/configuration") public ApiResponse<?> config(@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return responses.success(service.configuration(actor.id()),request);}
    @PostMapping("/users") public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal actor,@Valid @RequestBody Create body,HttpServletRequest request){return responses.success(service.create(actor.id(),body.operationId(),body.username(),body.password(),body.nickname()),request);}
    @PostMapping("/users/{id}/status") public ApiResponse<?> status(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@Valid @RequestBody Status body,HttpServletRequest request){service.status(actor.id(),id,body.version(),body.enabled());return responses.success(true,request);}
    @PostMapping("/users/{id}/permission") public ApiResponse<?> permission(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@Valid @RequestBody Permission body,HttpServletRequest request){service.permission(actor.id(),id,body.version(),body.enabled(),body.totalLimit());return responses.success(true,request);}
    @PostMapping("/users/{id}/invites/{invite}/revoke") public ApiResponse<?> revoke(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@PathVariable UUID invite,@Valid @RequestBody Version body,HttpServletRequest request){service.revoke(actor.id(),id,body.version(),invite);return responses.success(true,request);}
    @PostMapping("/users/{id}/password-reset") public ApiResponse<?> reset(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable UUID id,@Valid @RequestBody Reset body,HttpServletRequest request){service.reset(actor.id(),id,body.version(),body.password());return responses.success(true,request);}
    public interface Strict { @JsonAnySetter default void unknown(String key,Object value){throw new ApiFailure(ErrorCode.VALIDATION_ERROR);} }
    public record Create(@NotNull UUID operationId,@NotNull @Size(max=100) String username,@NotNull @Size(max=72) String password,@Size(max=60) String nickname) implements Strict { @Override public String toString(){return "Create[redacted]";} }
    public record Status(@NotNull @Min(0) Long version,@NotNull Boolean enabled) implements Strict {}
    public record Permission(@NotNull @Min(0) Long version,@NotNull Boolean enabled,@NotNull @Min(0) @Max(10000) Integer totalLimit) implements Strict {}
    public record Version(@NotNull @Min(0) Long version) implements Strict {}
    public record Reset(@NotNull @Min(0) Long version,@NotNull @Size(max=72) String password) implements Strict { @Override public String toString(){return "Reset[redacted]";} }
}
