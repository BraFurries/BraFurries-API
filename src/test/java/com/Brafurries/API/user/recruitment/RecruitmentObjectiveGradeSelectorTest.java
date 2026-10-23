package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.Grade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecruitmentObjectiveGradeSelectorTest {
    Grade grade(int id,int points,int total) {
        return new Grade(id,points,total,1,2,new BigDecimal("70.00"),
            (long)points*100 >= (long)total*70,Instant.parse("2026-10-23T15:00:00Z"));
    }
    @Test void bestChoosesHighestRatioWithoutIntegerTruncation() {
        assertEquals(2,RecruitmentObjectiveGradeSelector.select(
            List.of(grade(1,7,10),grade(2,71,100),grade(3,700,1000)),"BEST")
            .attemptId());
    }
    @Test void bestKeepsEarlierAttemptOnEqualFraction() {
        assertEquals(1,RecruitmentObjectiveGradeSelector.select(
            List.of(grade(1,7,10),grade(2,70,100)),"BEST").attemptId());
    }
    @Test void lastUsesSubmissionOrderEvenIfEarlierWasBetter() {
        assertEquals(2,RecruitmentObjectiveGradeSelector.select(
            List.of(grade(1,9,10),grade(2,3,10)),"LAST").attemptId());
    }
    @Test void emptyHistoryAndInvalidStrategy() {
        assertNull(RecruitmentObjectiveGradeSelector.select(List.of(),"BEST"));
        assertThrows(IllegalStateException.class,
            ()->RecruitmentObjectiveGradeSelector.select(List.of(),"FIRST"));
    }
}
