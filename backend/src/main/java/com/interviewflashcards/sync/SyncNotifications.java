package com.interviewflashcards.sync;

import com.interviewflashcards.auth.AccountPrincipal;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/** Bounded long polls: no servlet thread or database connection is held while waiting. */
@Component
public class SyncNotifications {
    private final JdbcTemplate jdbc;
    private final LearningTime time;
    private final ApiResponses responses;
    private final Set<Waiting> waiting = new HashSet<>();
    private record Waiting(AccountPrincipal user, UUID epoch, long revision, String date, long deadline, HttpSession session,
                           HttpServletRequest request, DeferredResult<ApiResponse<?>> result) {}
    public SyncNotifications(JdbcTemplate jdbc, LearningTime time, ApiResponses responses) {
        this.jdbc=jdbc; this.time=time; this.responses=responses;
    }
    public synchronized DeferredResult<ApiResponse<?>> listen(AccountPrincipal user, UUID epoch, long revision,
                                                               String date, HttpServletRequest request) {
        if(revision<0) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        if(waiting.size()>=512 || waiting.stream().filter(w->w.user.id().equals(user.id())).count()>=8)
            throw new ApiFailure(ErrorCode.TASK_LIMITED);
        var result=new DeferredResult<ApiResponse<?>>(22_000L);
        var item=new Waiting(user,epoch,revision,date,System.nanoTime()+20_000_000_000L,request.getSession(false),request,result);
        waiting.add(item);
        result.onCompletion(()->remove(item));
        result.onTimeout(()->complete(item));
        result.onError(error->remove(item));
        // Check after registration so a commit between the check and registration cannot be lost.
        check(item,false);
        return result;
    }
    private synchronized void remove(Waiting item) { waiting.remove(item); }
    private void complete(Waiting item) { check(item,true); }
    private void check(Waiting item, boolean heartbeat) {
        try {
            if(item.session==null) throw new ApiFailure(ErrorCode.UNAUTHENTICATED);
            item.session.getCreationTime(); // Invalidated sessions must not receive a queued notification.
            var rows=jdbc.queryForList("select data_epoch,revision,enabled,credential_version,must_change_password from app_user where id=?",item.user.id());
            if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))
                    || ((Number)rows.getFirst().get("credential_version")).longValue()!=item.user.credentialVersion())
                throw new ApiFailure(ErrorCode.UNAUTHENTICATED);
            var row=rows.getFirst();
            if(Boolean.TRUE.equals(row.get("must_change_password"))) throw new ApiFailure(ErrorCode.PASSWORD_CHANGE_REQUIRED);
            String date=time.today().toString();
            boolean changed=!item.epoch.equals(row.get("data_epoch")) || item.revision!=((Number)row.get("revision")).longValue() || !date.equals(item.date);
            // Return a heartbeat after 20 seconds even when unchanged. Client rechecks using its cursor.
            if(heartbeat || changed || System.nanoTime()>=item.deadline) {
                item.result.setResult(responses.success(Map.of("dataEpoch",row.get("data_epoch"),"revision",row.get("revision"),"learningDate",date),item.request));
                remove(item);
            }
        } catch (ApiFailure failure) { item.result.setErrorResult(failure); remove(item); }
        catch (IllegalStateException expired) { item.result.setErrorResult(new ApiFailure(ErrorCode.UNAUTHENTICATED)); remove(item); }
        catch (org.springframework.dao.DataAccessException unavailable) { item.result.setErrorResult(new ApiFailure(ErrorCode.SERVICE_UNAVAILABLE)); remove(item); }
    }
    @Scheduled(fixedDelay=1000)
    public void poll() {
        List<Waiting> batch;
        synchronized(this) { batch=List.copyOf(waiting); }
        for(var item:batch) check(item,false);
    }
    @Scheduled(fixedDelay=3_600_000, initialDelay=3_600_000)
    @org.springframework.transaction.annotation.Transactional
    public void prune() {
        // Both reads in changes() use repeatable read, so pruning cannot split a response.
        jdbc.update("delete from sync_revision where created_at<current_timestamp-interval '7 days'");
        jdbc.update("delete from sync_row_change where created_at<current_timestamp-interval '7 days'");
    }
}
