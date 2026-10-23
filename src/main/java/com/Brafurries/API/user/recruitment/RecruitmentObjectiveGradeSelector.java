package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.Grade;
import java.util.List;

/** Shared exact-rational BEST/LAST selection for preview and final decision. */
public final class RecruitmentObjectiveGradeSelector {
    private RecruitmentObjectiveGradeSelector() {}

    /** Ordered by attempt_number ascending; earliest attempt wins equal-ratio BEST ties. */
    public static Grade select(List<Grade> submitted, String strategy) {
        if (!"BEST".equals(strategy) && !"LAST".equals(strategy)) {
            throw new IllegalStateException("Estratégia congelada inválida");
        }
        if (submitted.isEmpty()) return null;
        if ("LAST".equals(strategy)) return submitted.get(submitted.size()-1);

        Grade best = null;
        for (Grade grade : submitted) {
            if (grade.possiblePoints()<=0 || grade.earnedPoints()<0 ||
                grade.earnedPoints()>grade.possiblePoints()) {
                throw new IllegalStateException("Pontuação persistida inválida");
            }
            if (best==null ||
                (long)grade.earnedPoints()*best.possiblePoints() >
                (long)best.earnedPoints()*grade.possiblePoints()) {
                best=grade;
            }
        }
        return best;
    }
}
