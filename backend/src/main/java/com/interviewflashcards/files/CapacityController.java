package com.interviewflashcards.files;
import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public record CapacityController(CapacityService capacity, ApiResponses responses) {
    @GetMapping("/api/storage/usage")
    public ApiResponse<java.util.Map<String,Long>> usage(@AuthenticationPrincipal AccountPrincipal user,HttpServletRequest request) {
        return responses.success(capacity.usage(user.id()),request);
    }
}
