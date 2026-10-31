package com.interviewflashcards.learning;

import static org.assertj.core.api.Assertions.*;
import com.interviewflashcards.learning.ReviewRules.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRulesTest {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private final List<Integer> intervals=List.of(1,3,7,14,30);
    private Instant at(String day) { return LocalDate.parse(day).atStartOfDay(zone).toInstant(); }
    private Result rate(State state,Rating rating,String day) { return ReviewRules.rate(state,rating,at(day),zone,intervals,state==null?1:state.version()+1); }
    @Test void rememberedAdvancesToThirtyDayCeilingAcrossYearsAndOverdueDates() {
        State state=null;LocalDate day=LocalDate.of(2026,12,31);
        for(int i=0;i<8;i++) {
            var r=rate(state,Rating.REMEMBERED,day.toString());
            assertThat(r.replay()).isFalse();assertThat(r.state().stage()).isEqualTo(Math.min(i,4));
            assertThat(r.state().dueDate()).isEqualTo(day.plusDays(intervals.get(Math.min(i,4))));
            state=r.state();day=state.dueDate().plusDays(4);
        }
        assertThat(state.firstLearnedAt()).isEqualTo(at("2026-12-31"));
    }
    @Test void vagueFloorsAtMinusOneAndForgotResetsAndAddsOneLapse() {
        var vague=rate(null,Rating.VAGUE,"2028-02-28").state();
        assertThat(vague.stage()).isEqualTo(-1);assertThat(vague.dueDate()).isEqualTo("2028-02-29");
        var next=rate(vague,Rating.REMEMBERED,"2028-02-29").state();
        var forgot=rate(next,Rating.FORGOT,"2028-03-01").state();
        assertThat(forgot.stage()).isEqualTo(-1);assertThat(forgot.lapseCount()).isEqualTo(1);assertThat(forgot.dueDate()).isEqualTo("2028-03-02");
        assertThat(rate(forgot,Rating.VAGUE,"2028-03-02").state().stage()).isEqualTo(-1);
    }
    @Test void everySameDayRatingPreservesStageAndLapsesAndNeverDelaysDueDate() {
        var initial=new State(at("2026-01-01"),at("2026-10-31"),LocalDate.of(2026,11,30),4,Rating.REMEMBERED,3,10);
        for(var rating:Rating.values()) {
            var r=rate(initial,rating,"2026-10-31");assertThat(r.replay()).isTrue();
            assertThat(r.state().stage()).isEqualTo(4);assertThat(r.state().lapseCount()).isEqualTo(3);
            assertThat(r.state().dueDate()).isEqualTo(rating==Rating.REMEMBERED?LocalDate.of(2026,11,30):LocalDate.of(2026,11,1));
        }
    }
    @Test void shanghaiMidnightChangesDayWhileUtcStillSameDate() {
        var before=ReviewRules.rate(null,Rating.REMEMBERED,Instant.parse("2026-10-31T15:59:59Z"),zone,intervals,1).state();
        var after=ReviewRules.rate(before,Rating.REMEMBERED,Instant.parse("2026-10-31T16:00:00Z"),zone,intervals,2);
        assertThat(after.replay()).isFalse();assertThat(after.state().stage()).isEqualTo(1);assertThat(after.state().dueDate()).isEqualTo("2026-11-04");
    }
    @Test void dueComparisonIncludesOverdueWithoutChangingStateAndParameterChangesNeedVersion() {
        var state=rate(null,Rating.FORGOT,"2026-01-31").state();
        assertThat(ReviewRules.due(state,LocalDate.of(2026,2,1))).isTrue();assertThat(ReviewRules.due(state,LocalDate.of(2026,2,10))).isTrue();
        assertThat(ReviewRules.due(null,LocalDate.of(2026,2,10))).isFalse();assertThat(state.dueDate()).isEqualTo("2026-02-01");
        assertThatThrownBy(()->new ReviewConfiguration("rules-v1",List.of(1,2,3,4,5))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReviewConfiguration("rules-v2",List.of(1,2,3,4,5)).version()).isEqualTo("rules-v2");
    }
}
