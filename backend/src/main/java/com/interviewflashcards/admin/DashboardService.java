package com.interviewflashcards.admin;

import com.interviewflashcards.auth.*;
import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;private final Clock clock;private final Path root;
    public DashboardService(JdbcTemplate jdbc,Clock clock,AppProperties app){this.jdbc=jdbc;this.clock=clock;root=Path.of(app.storage().root()).toAbsolutePath();}
    private void authorize(UUID actor){
        var rows=jdbc.queryForList("select role,enabled,must_change_password from app_user where id=? and space_kind='PERSONAL'",actor);
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")) || Boolean.TRUE.equals(rows.getFirst().get("must_change_password")) || !Capabilities.forRole((String)rows.getFirst().get("role")).contains(Capabilities.DASHBOARD))throw new ApiFailure(ErrorCode.FORBIDDEN);
    }
    // Recomputed from effective events, including restored history; never increment-only learning counters.
    private static final String DAILY="""
        with effective as (
          select e.user_id,e.card_id,(e.created_at at time zone 'Asia/Shanghai')::date as day
          from review_event e join app_user u on u.id=e.user_id and u.space_kind='PERSONAL'
          where e.kind in ('RATE','CORRECT') and not exists(select 1 from review_event x where x.user_id=e.user_id and x.undo_of=e.id)
        ), firsts as (select user_id,card_id,min(day) first_day from effective group by user_id,card_id),
        learning as (
          select e.user_id,e.day,count(distinct e.card_id) filter(where f.first_day=e.day) new_count,
            count(distinct e.card_id) filter(where f.first_day<e.day) review_count
          from effective e join firsts f using(user_id,card_id) group by e.user_id,e.day
        ), daily as (
          select coalesce(a.user_id,l.user_id) user_id,coalesce(a.day,l.day) as day,coalesce(a.active,false) active,
            coalesce(a.seconds,0) seconds,coalesce(l.new_count,0) new_count,coalesce(l.review_count,0) review_count
          from usage_daily a full join learning l using(user_id,day)
        )
        """;
    private LocalDate today(){return clock.instant().atZone(UsageService.ZONE).toLocalDate();}
    private void range(LocalDate from,LocalDate to){if(from==null || to==null || from.isAfter(to) || to.isAfter(today()) || from.isBefore(today().minusMonths(12)) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>365)throw new ApiFailure(ErrorCode.VALIDATION_ERROR);}
    private Map<String,Object> metadata(LocalDate from,LocalDate to){
        Instant start=jdbc.queryForObject("select started_at from usage_collection where id=1",Timestamp.class).toInstant();
        var m=new LinkedHashMap<String,Object>();m.put("timezone","Asia/Shanghai");m.put("from",from);m.put("to",to);m.put("updatedAt",clock.instant());m.put("collectionStartedAt",start);
        m.put("retainedFrom",today().minusMonths(12));m.put("activityCoverage",to.isBefore(start.atZone(UsageService.ZONE).toLocalDate())?"UNCOLLECTED":from.atStartOfDay(UsageService.ZONE).toInstant().isBefore(start)?"PARTIAL":"COLLECTED");return m;
    }
    private boolean uncollected(LocalDate day){return day.isBefore(jdbc.queryForObject("select started_at from usage_collection where id=1",Timestamp.class).toInstant().atZone(UsageService.ZONE).toLocalDate());}
    private Map<UUID,Long> storage(){
        Map<UUID,Long> result=new HashMap<>();
        // One batched metadata query; actual retained resources include backup/restore files.
        var rows=jdbc.queryForList("select user_id,storage_key from source_document union all select user_id,storage_key from asset union all select user_id,storage_key from share_resource union all select user_id,storage_key from backup_job where storage_key is not null union all select user_id,storage_key from restore_job where storage_key is not null");
        Set<UUID> seen=new HashSet<>();
        for(var r:rows){UUID key=(UUID)r.get("storage_key");if(!seen.add(key))continue;try{Path file=root.resolve(key.toString());if(Files.isRegularFile(file))result.merge((UUID)r.get("user_id"),Files.size(file),Long::sum);}catch(java.io.IOException e){throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE);}}
        return result;
    }
    private static final String ONLINE="""
        exists(select 1 from usage_stream s where s.user_id=u.id and not s.stopped and s.received_at>? and s.credential_version=u.credential_version) and u.enabled and not u.must_change_password
        """;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> overview(UUID actor,LocalDate from,LocalDate to){
        authorize(actor);range(from,to);var result=metadata(from,to);var date=today();
        var accounts=jdbc.queryForMap("select count(*) users,count(*) filter(where enabled) enabled,count(*) filter(where not enabled) disabled,count(*) filter(where (created_at at time zone 'Asia/Shanghai')::date=?) today_new_users,count(*) filter(where "+ONLINE+") online_users from app_user u where space_kind='PERSONAL'",date,Timestamp.from(clock.instant().minusSeconds(90)));
        var counts=jdbc.queryForMap(DAILY+"select count(distinct user_id) filter(where active and day between ? and ?) active_users,count(distinct user_id) filter(where new_count+review_count>0 and day between ? and ?) learning_users,coalesce(sum(seconds) filter(where day between ? and ?),0) seconds,coalesce(sum(new_count) filter(where day between ? and ?),0) new_count,coalesce(sum(review_count) filter(where day between ? and ?),0) review_count,count(distinct user_id) filter(where active and day=?) today_active,count(distinct user_id) filter(where active and day between ? and ?) week_active,count(distinct user_id) filter(where active and day between ? and ?) month_active from daily",from,to,from,to,from,to,from,to,from,to,date,date.minusDays(6),date,date.minusDays(29),date);
        counts.putAll(accounts);counts.put("storage_bytes",storage().values().stream().mapToLong(Long::longValue).sum());
        counts.put("failed_imports",jdbc.queryForObject("select count(*) from import_job where status='FAILED'",Long.class));
        if(uncollected(to)){for(String field:List.of("seconds","active_users"))counts.put(field,null);}
        result.put("summary",counts);
        result.put("days",days(null,from,to));return result;
    }
    private List<Map<String,Object>> days(UUID user,LocalDate from,LocalDate to){
        String filter=user==null?"":" and user_id=?";var args=new ArrayList<Object>(List.of(from,to));if(user!=null)args.add(user);
        var rows=jdbc.queryForList(DAILY+"select day,count(distinct user_id) filter(where active) active_users,count(distinct user_id) filter(where new_count+review_count>0) learning_users,sum(seconds) seconds,sum(new_count) new_count,sum(review_count) review_count from daily where day between ? and ?"+filter+" group by day order by day",args.toArray());
        Map<LocalDate,Map<String,Object>> indexed=new HashMap<>();for(var row:rows)indexed.put(((java.sql.Date)row.get("day")).toLocalDate(),row);
        LocalDate start=jdbc.queryForObject("select started_at from usage_collection where id=1",Timestamp.class).toInstant().atZone(UsageService.ZONE).toLocalDate();
        List<Map<String,Object>> result=new ArrayList<>();for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)){
            var row=new LinkedHashMap<String,Object>();row.put("day",day);for(String key:List.of("active_users","learning_users","seconds","new_count","review_count"))row.put(key,indexed.getOrDefault(day,Map.of()).getOrDefault(key,0));
            if(day.isBefore(start)){row.put("active_users",null);row.put("seconds",null);}result.add(row);
        }return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> users(UUID actor,LocalDate from,LocalDate to,String search,String sort,int page,int size){
        authorize(actor);range(from,to);if(search.length()>100 || page<0 || page>100000 || size<1 || size>50 || !Set.of("recent","duration").contains(sort))throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
        String where=" where u.space_kind='PERSONAL' and (position(lower(?) in lower(u.username))>0 or position(lower(?) in lower(coalesce(u.nickname,'')))>0)";
        String sql=DAILY+"""
            , totals as (select user_id,count(*) filter(where active) active_days,sum(seconds) seconds,sum(new_count) new_count,sum(review_count) review_count from daily where day between ? and ? group by user_id),
            claims as (select user_id,count(*) claims from platform_library_claim where created_at>=? and created_at<? group by user_id)
            select u.id,u.username,u.nickname,u.enabled,u.last_login_at,a.last_active_at,
            """+ONLINE+" online,coalesce(t.active_days,0) active_days,coalesce(t.seconds,0) seconds,coalesce(t.new_count,0) new_count,coalesce(t.review_count,0) review_count,coalesce(c.claims,0) claims from app_user u left join usage_user a on a.user_id=u.id left join totals t on t.user_id=u.id left join claims c on c.user_id=u.id"+where+" order by "+(sort.equals("duration")?"seconds desc,":"")+"a.last_active_at desc nulls last,u.id limit ? offset ?";
        var rows=jdbc.queryForList(sql,from,to,Timestamp.from(from.atStartOfDay(UsageService.ZONE).toInstant()),Timestamp.from(to.plusDays(1).atStartOfDay(UsageService.ZONE).toInstant()),Timestamp.from(clock.instant().minusSeconds(90)),search,search,size,page*size);
        var bytes=storage();boolean missing=uncollected(to);for(var row:rows){row.put("storage_bytes",bytes.getOrDefault((UUID)row.get("id"),0L));if(missing){row.put("seconds",null);row.put("active_days",null);}}
        var result=metadata(from,to);result.put("items",rows);result.put("total",jdbc.queryForObject("select count(*) from app_user u"+where,Long.class,search,search));result.put("page",page);result.put("size",size);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> user(UUID actor,UUID target,LocalDate from,LocalDate to){
        authorize(actor);range(from,to);var rows=jdbc.queryForList("select id,username,nickname from app_user where id=? and space_kind='PERSONAL'",target);if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        var result=metadata(from,to);result.put("user",rows.getFirst());result.put("days",days(target,from,to));return result;
    }
}
