package com.interviewflashcards.admin;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/dashboard")
public record DashboardController(DashboardService dashboard,ApiResponses responses){
    @GetMapping public ApiResponse<?> overview(@AuthenticationPrincipal AccountPrincipal user,@RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletRequest request){return responses.success(dashboard.overview(user.id(),from,to),request);}
    @GetMapping("/users") public ApiResponse<?> users(@AuthenticationPrincipal AccountPrincipal user,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="recent") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,HttpServletRequest request){return responses.success(dashboard.users(user.id(),from,to,search,sort,page,size),request);}
    @GetMapping("/users/{target}") public ApiResponse<?> user(@AuthenticationPrincipal AccountPrincipal user,@PathVariable UUID target,@RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletRequest request){return responses.success(dashboard.user(user.id(),target,from,to),request);}
}
