package com.interviewflashcards.learning;

import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReviewPolicyService {
    public record Policy(long version,String algorithmVersion,List<Integer> intervals,String reason,Instant createdAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReviewConfiguration defaults;
    private final LearningTime time;
    private final MutationService mutations;
    public ReviewPolicyService(JdbcTemplate jdbc,ObjectMapper mapper,ReviewConfiguration defaults,LearningTime time,MutationService mutations) {
        this.jdbc=jdbc;this.mapper=mapper;this.defaults=defaults;this.time=time;this.mutations=mutations;
    }
    private Policy policy(Map<String,Object> row) {
        try { return new Policy(((Number)row.get("version")).longValue(),row.get("algorithm_version").toString(),
            mapper.readerForListOf(Integer.class).readValue(row.get("intervals").toString()),row.get("reason").toString(),((Timestamp)row.get("created_at")).toInstant());
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    public Policy active(UUID user) {
        var rows=jdbc.queryForList("select * from review_policy where user_id=? order by version desc limit 1",user);
        return rows.isEmpty()?new Policy(0,defaults.version(),defaults.intervals(),"部署默认参数",null):policy(rows.getFirst());
    }
    public Policy original(JsonNode snapshot,String algorithmVersion) {
        if(snapshot.path("intervals").isArray()) {
            try { return new Policy(snapshot.path("version").asLong(),algorithmVersion,
                mapper.readerForListOf(Integer.class).readValue(snapshot.path("intervals")),snapshot.path("reason").asText(),
                snapshot.path("createdAt").isTextual()?Instant.parse(snapshot.path("createdAt").asText()):null);
            } catch(IOException e) { throw new IllegalStateException(e); }
        }
        // The shipped rules-v1 intervals are known. Other legacy versions have no recoverable evidence.
        if(algorithmVersion.equals("rules-v1")) return new Policy(0,algorithmVersion,List.of(1,3,7,14,30),"首版固定参数",null);
        throw new ApiFailure(ErrorCode.INVALID_REQUEST,Map.of("reason","原评分缺少参数快照，请接受当前结果后重新练习"));
    }
    public static boolean validIntervals(List<Integer> values) {
        if(values==null || values.size()!=5 || values.stream().anyMatch(n->n==null || n<1 || n>365)) return false;
        for(int i=1;i<5;i++) if(values.get(i)<values.get(i-1)) return false;
        return true;
    }
    public JsonNode save(UUID user,LearningController.ReviewParameters c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"review.parameters",c,()->{
            var current=active(user);
            if(c.expectedVersion()==null || c.expectedVersion()!=current.version())
                throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",current));
            if(!validIntervals(c.intervals()) || c.reason()==null || c.reason().isBlank() || c.reason().length()>500) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            if(c.intervals().equals(current.intervals())) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var next=new Policy(current.version()+1,"personal-"+UUID.randomUUID(),List.copyOf(c.intervals()),c.reason().strip(),time.now());
            jdbc.update("insert into review_policy(user_id,version,algorithm_version,intervals,reason,created_at) values(?,?,?,?::jsonb,?,?)",
                user,next.version(),next.algorithmVersion(),mapper.writeValueAsString(next.intervals()),next.reason(),Timestamp.from(next.createdAt()));
            return new MutationService.Change(Map.of("current",next),next.version(),true);
        });
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> report(UUID user,int days) {
        if(!Set.of(30,90,180).contains(days)) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        var today=time.today();var from=today.minusDays(days-1L);
        // Attribute the NEXT due review's self-assessment to the policy that scheduled it.
        // RELEARN breaks the observation chain; same-day replays never create an observation.
        var outcomes=jdbc.queryForList("""
            with effective as (
                select e.* from review_event e where user_id=? and kind in ('RATE','CORRECT','RELEARN')
                and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            ), ordered as (
                select e.*,lag(algorithm_version) over w as prior_version,lag(algorithm_parameters) over w as prior_parameters,
                lag(after_state) over w as prior_state,lag(learning_date) over w as prior_day,lag(kind) over w as prior_kind
                from effective e where not is_replay or kind='RELEARN'
                    or (after_state->>'dueDate') is distinct from (before_state->>'dueDate')
                window w as (partition by card_id order by event_order)
            ), observations as (
                select *,learning_date-(prior_state->>'dueDate')::date as late_days from ordered
                where kind in ('RATE','CORRECT') and not is_replay and prior_kind in ('RATE','CORRECT')
                and before_state<>'null'::jsonb and learning_date>prior_day
                and learning_date>=(prior_state->>'dueDate')::date and learning_date between ? and ?
            )
            select prior_version as "algorithmVersion",prior_parameters as parameters,count(*) as observations,
                count(distinct card_id) as cards,count(*) filter(where rating='FORGOT') as forgot,
                count(*) filter(where rating='VAGUE') as vague,count(*) filter(where rating='REMEMBERED') as remembered,
                count(*) filter(where late_days=0) as "onTime",count(*) filter(where late_days>0) as late,
                round(avg(late_days),1) as "averageLateDays",
                round(avg((prior_state->>'dueDate')::date-prior_day),1) as "averagePlannedDays"
            from observations group by prior_version,prior_parameters order by prior_version,prior_parameters::text
            """,user,from,today);
        outcomes.forEach(row->{
            try { row.put("parameters",mapper.readTree(row.get("parameters").toString())); }
            catch(IOException e) { throw new IllegalStateException(e); }
        });
        var activity=jdbc.queryForList(EffectiveReviews.CTE+"""
            select algorithm_version as "algorithmVersion",count(*) as assessments,
                count(*) filter(where before_state='null'::jsonb) as "firstAssessments",
                count(*) filter(where is_replay) as replays
            from effective where learning_date between ? and ? group by algorithm_version order by algorithm_version
            """,user,from,today);
        var history=jdbc.queryForList("select * from review_policy where user_id=? order by version desc limit 20",user).stream().map(this::policy).toList();
        return Map.of("current",active(user),"history",history,"activity",activity,"outcomes",outcomes,
            "from",from,"to",today,"learningTimezone",time.learningZone().getId(),"generatedAt",time.now());
    }
}
