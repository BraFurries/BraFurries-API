package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;

import com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecruitmentObjectiveScorerTest {
    private final RecruitmentObjectiveScorer scorer=new RecruitmentObjectiveScorer();
    private final ObjectMapper json=new ObjectMapper();
    Question q(String id,QuestionType type,int points,String... correct) {
        return new Question(id,id,type,true,
            type==QuestionType.SINGLE_CHOICE||type==QuestionType.MULTIPLE_CHOICE
                ?List.of(new Option("a","A"),new Option("b","B"),new Option("c","C"))
                :List.of(),null,null,points,List.of(correct));
    }
    JsonNode value(String v) throws Exception {return json.readTree(v);}

    @Test void fullExactMultipleChoiceAndNumericSemanticEquality() throws Exception {
        var form=new RecruitmentFormDefinition(List.of(
            q("multi",QuestionType.MULTIPLE_CHOICE,40,"a","c"),
            q("number",QuestionType.NUMBER,30,"2.00"),
            q("boolean",QuestionType.YES_NO,30,"true")
        ));
        var score=scorer.score(form,Map.of(
            "multi",value("[\"c\",\"a\"]"),
            "number",value("2"),
            "boolean",value("true")
        ),new BigDecimal("70.00"));
        assertEquals(100,score.earnedPoints());
        assertEquals(100,score.possiblePoints());
        assertEquals(3,score.correctQuestions());
        assertTrue(score.passed());
    }

    @Test void partialMultipleChoiceGetsZeroForThatQuestion() throws Exception {
        var form=new RecruitmentFormDefinition(List.of(
            q("multi",QuestionType.MULTIPLE_CHOICE,60,"a","c"),
            q("single",QuestionType.SINGLE_CHOICE,40,"b")
        ));
        var score=scorer.score(form,Map.of(
            "multi",value("[\"a\"]"),
            "single",value("\"b\"")
        ),new BigDecimal("40.00"));
        assertEquals(40,score.earnedPoints());
        assertEquals(1,score.correctQuestions());
        assertTrue(score.passed());
    }

    @Test void exactCutoffPassesButJustBelowItDoesNot() throws Exception {
        var form=new RecruitmentFormDefinition(List.of(
            q("a",QuestionType.YES_NO,69,"true"),
            q("b",QuestionType.YES_NO,31,"true")
        ));
        var response=Map.of("a",value("true"),"b",value("false"));
        assertFalse(scorer.score(form,response,new BigDecimal("70.00")).passed());
        assertTrue(scorer.score(form,response,new BigDecimal("69.00")).passed());
    }

    @Test void missingOptionalAnswerIsNeverCountedAsCorrect() {
        var form=new RecruitmentFormDefinition(List.of(
            q("q",QuestionType.SINGLE_CHOICE,10,"a")
        ));
        assertEquals(0,scorer.score(form,Map.of(),BigDecimal.ONE).earnedPoints());
    }

    @Test void malformedScorePolicyAndAnswerKeyFailClosed() throws Exception {
        var missingKey=new RecruitmentFormDefinition(List.of(
            q("q",QuestionType.SINGLE_CHOICE,10)
        ));
        assertThrows(IllegalStateException.class,()->
            scorer.score(missingKey,Map.of("q",value("\"a\"")),BigDecimal.TEN));
        var valid=new RecruitmentFormDefinition(List.of(
            q("q",QuestionType.SINGLE_CHOICE,10,"a")
        ));
        assertThrows(IllegalStateException.class,()->
            scorer.score(valid,Map.of("q",value("\"a\"")),BigDecimal.ZERO));
    }
}
