package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic grading against the PRIVATE frozen key, after candidate answer validation.
 * Full exact match only (no partial credit, negative points or client-provided grade).
 */
@Component
public class RecruitmentObjectiveScorer {
    public record Score(int earnedPoints,int possiblePoints,
                        int correctQuestions,int totalQuestions,boolean passed) {}

    public Score score(RecruitmentFormDefinition form,Map<String,JsonNode> answers,
                       BigDecimal passingPercentage) {
        if(form==null || form.questions()==null || form.questions().isEmpty() ||
            answers==null || passingPercentage==null ||
            passingPercentage.compareTo(BigDecimal.ONE)<0 ||
            passingPercentage.compareTo(new BigDecimal("100"))>0)
            throw new IllegalStateException("Política/gabarito de prova incompleto");
        int earned=0,total=0,correct=0;
        for(Question q:form.questions()) {
            if(q.points()==null || q.points()<1 || q.correctAnswers()==null ||
                q.correctAnswers().isEmpty() || total>1000000-q.points())
                throw new IllegalStateException("Gabarito de prova inválido");
            total+=q.points();
            if(matches(q,answers.get(q.id()))) {
                earned+=q.points();
                correct++;
            }
        }
        boolean passed=BigDecimal.valueOf(earned).multiply(BigDecimal.valueOf(100))
            .compareTo(passingPercentage.multiply(BigDecimal.valueOf(total)))>=0;
        return new Score(earned,total,correct,form.questions().size(),passed);
    }

    private boolean matches(Question q,JsonNode value) {
        if(value==null || value.isNull()) return false;
        var key=q.correctAnswers();
        return switch(q.type()) {
            case SINGLE_CHOICE -> value.isTextual() && key.size()==1
                && key.get(0).equals(value.asText());
            case MULTIPLE_CHOICE -> {
                if(!value.isArray() || value.size()!=key.size()) yield false;
                Set<String> submitted=new HashSet<>();
                for(JsonNode v:value) {
                    if(!v.isTextual() || !submitted.add(v.asText())) yield false;
                }
                yield submitted.equals(Set.copyOf(key));
            }
            case YES_NO -> value.isBoolean() && key.size()==1 &&
                Boolean.toString(value.booleanValue()).equals(key.get(0));
            case NUMBER,SCALE -> {
                if(!value.isNumber() || key.size()!=1) yield false;
                try {
                    yield value.decimalValue().compareTo(new BigDecimal(key.get(0)))==0;
                } catch(NumberFormatException badKey) {
                    throw new IllegalStateException("Gabarito numérico persistido inválido",badKey);
                }
            }
            default -> throw new IllegalStateException("Questão não objetiva no gabarito");
        };
    }
}
