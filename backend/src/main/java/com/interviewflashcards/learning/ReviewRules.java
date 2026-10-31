package com.interviewflashcards.learning;

import java.time.*;
import java.util.List;

/** Pure scheduling rules: dates and instants are supplied by the caller. */
public final class ReviewRules {
    private ReviewRules() {}
    public enum Rating { FORGOT, VAGUE, REMEMBERED }
    public record State(Instant firstLearnedAt, Instant lastReviewedAt, LocalDate dueDate,
                        int stage, Rating latestRating, int lapseCount, long version) {}
    public record Result(State state, boolean replay) {}
    public static boolean due(State state, LocalDate today) { return state != null && !state.dueDate().isAfter(today); }
    public static Result rate(State before, Rating rating, Instant now, ZoneId zone, List<Integer> intervals, long version) {
        LocalDate day=LocalDate.ofInstant(now,zone);
        boolean replay=before!=null && LocalDate.ofInstant(before.lastReviewedAt(),zone).equals(day);
        int stage=before==null?-1:before.stage(), lapses=before==null?0:before.lapseCount();
        LocalDate due;
        if(replay) {
            due=before.dueDate();
            if(rating!=Rating.REMEMBERED && day.plusDays(1).isBefore(due)) due=day.plusDays(1);
        } else {
            stage=switch(rating) { case REMEMBERED -> Math.min(stage+1,4); case VAGUE -> Math.max(stage-1,-1); case FORGOT -> -1; };
            if(rating==Rating.FORGOT) lapses++;
            due=day.plusDays(rating==Rating.REMEMBERED?intervals.get(stage):1);
        }
        return new Result(new State(before==null?now:before.firstLearnedAt(),now,due,stage,rating,lapses,version),replay);
    }
}
