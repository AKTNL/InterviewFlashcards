package com.interviewflashcards.admin;

import com.interviewflashcards.auth.*;
import com.interviewflashcards.common.web.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server receipt times only; all streams for one user serialize on a dedicated statistics row. */
@Service
public class UsageService {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public UsageService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Transactional
    public boolean heartbeat(AccountPrincipal user,String authId,UUID stream,long sequence,boolean active) {
        String hash=AccountRules.digest(authId);Instant now=clock.instant();
        jdbc.update("insert into usage_login(auth_hash,last_seen_at) values(?,?) on conflict do nothing",hash,Timestamp.from(now));
        if(Boolean.TRUE.equals(jdbc.queryForObject("select ended from usage_login where auth_hash=? for update",Boolean.class,hash)))throw new ApiFailure(ErrorCode.UNAUTHENTICATED);
        jdbc.update("update usage_login set last_seen_at=? where auth_hash=?",Timestamp.from(now),hash);
        jdbc.update("insert into usage_user(user_id) values(?) on conflict do nothing",user.id());
        var activity=jdbc.queryForMap("select last_active_at from usage_user where user_id=? for update",user.id());
        var account=jdbc.queryForMap("select enabled,credential_version,must_change_password from app_user where id=?",user.id());
        if(!Boolean.TRUE.equals(account.get("enabled")) || Boolean.TRUE.equals(account.get("must_change_password")) || ((Number)account.get("credential_version")).longValue()!=user.credentialVersion())throw new ApiFailure(ErrorCode.UNAUTHENTICATED);
        var rows=jdbc.queryForList("select sequence,received_at,stopped from usage_stream where user_id=? and auth_hash=? and stream_id=?",user.id(),hash,stream);
        if(!active){
            if(rows.isEmpty() && jdbc.queryForObject("select count(*) from usage_stream where user_id=? and received_at>?",Long.class,user.id(),Timestamp.from(now.minusSeconds(90)))>=32)throw new ApiFailure(ErrorCode.RATE_LIMITED);
            jdbc.update("insert into usage_stream(user_id,auth_hash,stream_id,credential_version,sequence,received_at,stopped) values(?,?,?,?,0,?,true) on conflict(user_id,auth_hash,stream_id) do update set stopped=true",user.id(),hash,stream,user.credentialVersion(),Timestamp.from(now));return true;
        }
        Instant previous=null;
        if(!rows.isEmpty()) {
            var row=rows.getFirst();previous=((Timestamp)row.get("received_at")).toInstant();
            if(Boolean.TRUE.equals(row.get("stopped")) || sequence<=((Number)row.get("sequence")).longValue() || Duration.between(previous,now).toMillis()<10_000)return false;
        } else {
            if(activity.get("last_active_at") instanceof Timestamp last && Duration.between(last.toInstant(),now).toMillis()<2_000)return false;
            if(jdbc.queryForObject("select count(*) from usage_stream where user_id=? and received_at>?",Long.class,user.id(),Timestamp.from(now.minusSeconds(90)))>=32)throw new ApiFailure(ErrorCode.RATE_LIMITED);
        }
        jdbc.update("insert into usage_stream(user_id,auth_hash,stream_id,credential_version,sequence,received_at) values(?,?,?,?,?,?) on conflict(user_id,auth_hash,stream_id) do update set sequence=excluded.sequence,received_at=excluded.received_at,credential_version=excluded.credential_version",user.id(),hash,stream,user.credentialVersion(),sequence,Timestamp.from(now));
        jdbc.update("update usage_user set last_active_at=? where user_id=?",Timestamp.from(now),user.id());
        LocalDate day=now.atZone(ZONE).toLocalDate();
        jdbc.update("insert into usage_daily(user_id,day,active) values(?,?,true) on conflict(user_id,day) do update set active=true",user.id(),day);
        if(previous!=null && !previous.isAfter(now) && Duration.between(previous,now).compareTo(Duration.ofSeconds(60))<=0) {
            Instant start=previous;
            while(start.isBefore(now)) {
                LocalDate date=start.atZone(ZONE).toLocalDate();
                Instant end=date.plusDays(1).atStartOfDay(ZONE).toInstant();if(end.isAfter(now))end=now;
                jdbc.update("""
                    insert into usage_daily(user_id,day,active,spans) values(?,?,true,tstzmultirange(tstzrange(?,?,'[)')))
                    on conflict(user_id,day) do update set active=true,spans=usage_daily.spans+excluded.spans
                    """,user.id(),date,Timestamp.from(start),Timestamp.from(end));
                jdbc.update("update usage_daily set seconds=(select coalesce(sum(extract(epoch from upper(r)-lower(r))),0) from unnest(spans) r) where user_id=? and day=?",user.id(),date);
                start=end;
            }
        }
        return true;
    }
    @Transactional
    public void endSession(String authId){
        String hash=AccountRules.digest(authId);
        jdbc.update("insert into usage_login(auth_hash,ended,last_seen_at) values(?,true,?) on conflict(auth_hash) do update set ended=true,last_seen_at=excluded.last_seen_at",hash,Timestamp.from(clock.instant()));
        jdbc.update("update usage_stream set stopped=true where auth_hash=?",hash);
    }
    @Scheduled(fixedDelayString="${app.usage.cleanup-ms:3600000}",initialDelayString="${app.usage.cleanup-ms:3600000}")
    @Transactional
    public void cleanup(){
        LocalDate today=clock.instant().atZone(ZONE).toLocalDate();
        jdbc.update("delete from usage_stream where received_at<?",Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        jdbc.update("delete from usage_login where last_seen_at<?",Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        jdbc.update("update usage_daily set spans=null where day<? and spans is not null",today.minusDays(30));
        jdbc.update("delete from usage_daily where day<?",today.minusMonths(12));
    }
}
