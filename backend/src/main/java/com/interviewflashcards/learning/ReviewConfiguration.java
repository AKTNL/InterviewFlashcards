package com.interviewflashcards.learning;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ReviewConfiguration(String version, List<Integer> intervals) {
    public ReviewConfiguration(@Value("${app.review.algorithm-version:rules-v1}") String version,
            @Value("${app.review.success-intervals:1,3,7,14,30}") List<Integer> intervals) {
        if(version.isBlank() || version.length()>60 || intervals.size()!=5 || intervals.stream().anyMatch(n->n<1))
            throw new IllegalArgumentException("Invalid review configuration");
        for(int i=1;i<5;i++) if(intervals.get(i)<intervals.get(i-1)) throw new IllegalArgumentException("Intervals must increase");
        if(version.equals("rules-v1") && !intervals.equals(List.of(1,3,7,14,30)))
            throw new IllegalArgumentException("Changed review parameters require a new algorithm version");
        this.version=version; this.intervals=List.copyOf(intervals);
    }
}
