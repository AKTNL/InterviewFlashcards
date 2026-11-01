package com.interviewflashcards.learning;

import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LearningTrendsService {
    private final JdbcTemplate jdbc;
    private final LearningTime time;
    public LearningTrendsService(JdbcTemplate jdbc, LearningTime time) { this.jdbc=jdbc; this.time=time; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> trends(UUID user,int weeks,int offset,int limit) {
        if(weeks<1 || weeks>52 || offset<0 || limit<1 || limit>100) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        var now=time.now();
        var today=LocalDate.ofInstant(now,time.learningZone());
        var from=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks-1L);
        // Daily distinct cards are summed into weeks. A card reviewed on two days counts twice,
        // while same-day replays only affect the explicitly labelled assessment counts.
        var weekly=jdbc.queryForList(EffectiveReviews.CTE+"""
            , days as (
                select e.learning_date as day,
                count(distinct e.card_id) filter(where f.first_day=e.learning_date) as new_count,
                count(distinct e.card_id) filter(where f.first_day<e.learning_date) as review_count,
                count(*) as assessments,
                count(*) filter(where e.rating='FORGOT') as forgot,
                count(*) filter(where e.rating='VAGUE') as vague,
                count(*) filter(where e.rating='REMEMBERED') as remembered
                from effective e join firsts f on f.card_id=e.card_id
                where e.learning_date between ? and ? group by e.learning_date
            )
            select w.day::date as "weekStart",least(w.day::date+6,?::date) as "weekEnd",
                count(d.day) as "learningDays",coalesce(sum(d.new_count),0)::bigint as "newCount",
                coalesce(sum(d.review_count),0)::bigint as "reviewCount",coalesce(sum(d.assessments),0)::bigint as assessments,
                coalesce(sum(d.forgot),0)::bigint as forgot,coalesce(sum(d.vague),0)::bigint as vague,
                coalesce(sum(d.remembered),0)::bigint as remembered
            from generate_series(?::date,?::date,interval '1 week') w(day)
            left join days d on d.day between w.day::date and w.day::date+6
            group by w.day order by w.day
            """,user,from,today,today,from,today);

        // Compare the last effective state before the range with the current effective state.
        // No historical content snapshots are invented after deletion/reset/restore.
        var chapters=jdbc.queryForList(EffectiveReviews.CTE+"""
            , baseline as (
                select distinct on(card_id) card_id,rating from effective where learning_date<? order by card_id,event_order desc
            ), latest as (
                select distinct on(card_id) card_id,rating from effective where learning_date<=? order by card_id,event_order desc
            ), activity as (
                select e.card_id,count(distinct e.learning_date) filter(where f.first_day=e.learning_date) as new_count,
                count(distinct e.learning_date) filter(where f.first_day<e.learning_date) as review_count,
                count(*) as assessments from effective e join firsts f on f.card_id=e.card_id
                where e.learning_date between ? and ? group by e.card_id
            )
            select ch.id,ch.name,m.name as "moduleName",d.name as "deckName",
                exists(select 1 from learning_preference_chapter pc where pc.user_id=ch.user_id and pc.chapter_id=ch.id) as selected,
                count(c.id) as total,count(b.card_id) as "learnedBefore",count(l.card_id) as learned,
                count(b.card_id) filter(where b.rating in ('FORGOT','VAGUE')) as "weakBefore",
                count(l.card_id) filter(where l.rating in ('FORGOT','VAGUE')) as weak,
                count(l.card_id) filter(where l.rating='FORGOT') as forgot,
                count(l.card_id) filter(where l.rating='VAGUE') as vague,
                count(l.card_id) filter(where l.rating='REMEMBERED') as remembered,
                coalesce(sum(a.new_count),0)::bigint as "newCount",coalesce(sum(a.review_count),0)::bigint as "reviewCount",
                coalesce(sum(a.assessments),0)::bigint as assessments
            from chapter ch join module m on m.user_id=ch.user_id and m.id=ch.module_id
            join deck d on d.user_id=m.user_id and d.id=m.deck_id
            left join card c on c.user_id=ch.user_id and c.chapter_id=ch.id
            left join baseline b on b.card_id=c.id left join latest l on l.card_id=c.id left join activity a on a.card_id=c.id
            where ch.user_id=? and ch.check_status='IMPORTED'
            group by ch.id,ch.user_id,ch.name,m.name,d.name,d.id,m.sort_order,m.id,ch.sort_order
            order by d.id,m.sort_order,m.id,ch.sort_order,ch.id
            """,user,from,today,from,today,user);

        // lapse_count belongs to the scheduling state: same-day FORGOT replays do not add lapses.
        var forgotten=jdbc.queryForList("""
            select c.id,c.question,ch.name as "chapterName",m.name as "moduleName",d.name as "deckName",
                r.lapse_count as "lapseCount",r.latest_rating as "latestRating",r.due_date as "dueDate"
            from review_state r join card c on c.user_id=r.user_id and c.id=r.card_id
            join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id
            join module m on m.user_id=ch.user_id and m.id=ch.module_id
            join deck d on d.user_id=m.user_id and d.id=m.deck_id
            where r.user_id=? and r.lapse_count>0 and ch.check_status='IMPORTED'
            order by r.lapse_count desc,r.due_date,c.id limit ? offset ?
            """,user,limit,offset);
        var total=jdbc.queryForObject("""
            select count(*) from review_state r join card c on c.user_id=r.user_id and c.id=r.card_id
            join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id
            where r.user_id=? and r.lapse_count>0 and ch.check_status='IMPORTED'
            """,Long.class,user);
        return Map.of("learningDate",today,"learningTimezone",time.learningZone().getId(),"from",from,"to",today,
            "generatedAt",now,"weeks",weekly,"chapters",chapters,
            "forgotten",Map.of("items",forgotten,"total",total,"offset",offset,"limit",limit));
    }
}
