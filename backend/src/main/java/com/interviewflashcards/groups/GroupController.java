package com.interviewflashcards.groups;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
public record GroupController(GroupService service, ApiResponses responses) {
    public record Create(@NotNull UUID id, @NotBlank @Size(max=80) String name, @AssertTrue boolean confirmed) {}
    public record Invite(@NotNull UUID id, @NotBlank @Size(max=100) String recipient, @Min(1) @Max(168) int expiresInHours) {}
    public record Join(@AssertTrue boolean confirmed) {}
    public record Consent(@NotNull @PositiveOrZero Long expectedVersion, boolean sharing) {}
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal AccountPrincipal u,HttpServletRequest r) {
        return responses.success(service.list(u.id()),r);
    }
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal AccountPrincipal u,@Valid @RequestBody Create c,HttpServletRequest r) {
        return responses.success(service.create(u.id(),c),r);
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) {
        return responses.success(service.detail(u.id(),id),r);
    }
    @PostMapping("/{id}/invites") public ApiResponse<?> invite(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@Valid @RequestBody Invite c,HttpServletRequest r) {
        return responses.success(service.invite(u.id(),id,c),r);
    }
    @PostMapping("/{id}/invites/{invite}/revoke") public ApiResponse<?> revoke(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@PathVariable UUID invite,HttpServletRequest r) {
        service.revoke(u.id(),id,invite);return responses.success(true,r);
    }
    @PostMapping("/{id}/join/{invite}") public ApiResponse<?> join(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@PathVariable UUID invite,@Valid @RequestBody Join c,HttpServletRequest r) {
        service.join(u.id(),id,invite,c);return responses.success(true,r);
    }
    @PostMapping("/{id}/consent") public ApiResponse<?> consent(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,@Valid @RequestBody Consent c,HttpServletRequest r) {
        return responses.success(service.consent(u.id(),id,c),r);
    }
    @PostMapping("/{id}/leave") public ApiResponse<?> leave(@AuthenticationPrincipal AccountPrincipal u,@PathVariable UUID id,HttpServletRequest r) {
        service.leave(u.id(),id);return responses.success(true,r);
    }
}
