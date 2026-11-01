package com.interviewflashcards.cards;

import com.interviewflashcards.common.time.LearningTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DeckManagementService {
    private final JdbcTemplate jdbc;
    private final LearningTime time;
    public DeckManagementService(JdbcTemplate jdbc, LearningTime time) { this.jdbc=jdbc; this.time=time; }

    // A title match is only a review hint. Answers and learning records remain independent.
    static String titleKey(String alias) {
        return "lower(regexp_replace("+alias+".question,'[[:space:]]+','','g'))";
    }
    static final String DUPLICATE = "exists (select 1 from card other where other.user_id=c.user_id and other.id<>c.id and "
        +titleKey("other")+"="+titleKey("c")+" and "+titleKey("c")+"<>'')";

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> overview(UUID user) {
        var today=time.today();
        var decks=jdbc.queryForList("""
            with cards as (
                select m.deck_id,c.id,r.id as learned,r.due_date,r.latest_rating,
                """+DUPLICATE+" as duplicate from card c join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id "
                +"join module m on m.user_id=ch.user_id and m.id=ch.module_id "
                +"left join review_state r on r.user_id=c.user_id and r.card_id=c.id where c.user_id=?), "+"""
            counts as (
                select deck_id,count(*) as total,count(learned) as learned,
                count(*) filter(where due_date<=?) as due,
                count(*) filter(where latest_rating in ('FORGOT','VAGUE')) as weak,
                count(*) filter(where duplicate) as duplicates from cards group by deck_id
            ), chapters as (
                select m.deck_id,count(*) as chapters,count(*) filter(where ch.check_status='IMPORTED') as imported,
                count(p.chapter_id) as selected
                from chapter ch join module m on m.user_id=ch.user_id and m.id=ch.module_id
                left join learning_preference_chapter p on p.user_id=ch.user_id and p.chapter_id=ch.id
                where ch.user_id=? group by m.deck_id
            )
            select d.id,d.name,coalesce(c.total,0) as total,coalesce(c.learned,0) as learned,
            coalesce(c.due,0) as due,coalesce(c.weak,0) as weak,coalesce(c.duplicates,0) as duplicates,
            coalesce(ch.chapters,0) as chapters,coalesce(ch.imported,0) as imported,coalesce(ch.selected,0) as selected
            from deck d left join counts c on c.deck_id=d.id left join chapters ch on ch.deck_id=d.id
            where d.user_id=? order by d.name,d.id
            """,user,today,user,user);
        var sources=jdbc.queryForList("""
            select j.deck_id as "deckId",s.id,s.original_filename as name,s.sha256,s.shared_copy as "sharedCopy",
            j.id as "jobId",j.status,
            (select count(distinct j2.deck_id) from source_document s2
             join import_job j2 on j2.user_id=s2.user_id and j2.source_id=s2.id
             where s2.user_id=s.user_id and s2.sha256=s.sha256 and j2.deck_id<>j.deck_id) as "sameFileDecks"
            from import_job j join source_document s on s.user_id=j.user_id and s.id=j.source_id
            where j.user_id=? and j.deck_id is not null order by j.deck_id,s.id
            """,user);
        return Map.of("decks",decks,"sources",sources,"learningDate",today);
    }
}
