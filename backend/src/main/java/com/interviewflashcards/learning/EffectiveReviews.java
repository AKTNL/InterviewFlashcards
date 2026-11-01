package com.interviewflashcards.learning;

/** The same effective-event definition is used by summaries and personal trends. */
final class EffectiveReviews {
    private EffectiveReviews() {}
    static final String CTE = """
        with effective as (
            select e.* from review_event e where e.user_id=? and e.kind in ('RATE','CORRECT')
            and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
        ), firsts as (select card_id,min(learning_date) as first_day from effective group by card_id)
        """;
}
