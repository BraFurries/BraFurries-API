package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RecruitmentFormValidatorTest {
    private final RecruitmentFormValidator validator = new RecruitmentFormValidator();

    private Question q(String id, QuestionType type, List<Option> options, Integer points,
                       List<String> answers) {
        return new Question(id, "Pergunta " + id, type, true, options, null, null, points, answers);
    }

    private List<Option> options() {
        return List.of(new Option("a", "A"), new Option("b", "B"));
    }

    private void invalid(RecruitmentFormDefinition form, boolean staff,
                         com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType type) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> validator.validate(form, staff, type));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test void acceptsIndependentCandidateAndStaffAssessments() {
        var candidate = new RecruitmentFormDefinition(List.of(
            q("why", QuestionType.LONG_TEXT, null, null, null),
            q("availability", QuestionType.MULTIPLE_CHOICE, options(), null, null)
        ));
        var staff = new RecruitmentFormDefinition(List.of(
            new Question("empathy", "Empatia", QuestionType.SCALE, true,
                null, 1, 5, null, null),
            q("summary", QuestionType.LONG_TEXT, null, null, null)
        ));
        assertEquals(2, validator.validate(candidate, false, HUMAN_EVALUATION).questions().size());
        assertEquals(2, validator.validate(staff, true, HUMAN_EVALUATION).questions().size());
        assertNull(validator.validate(null, true, HUMAN_EVALUATION));
    }

    @Test void acceptsScoredObjectiveExamAndKeepsAnswerKeysForInternalUse() {
        var form = new RecruitmentFormDefinition(List.of(
            q("single", QuestionType.SINGLE_CHOICE, options(), 10, List.of("a")),
            q("multiple", QuestionType.MULTIPLE_CHOICE, options(), 20, List.of("a", "b")),
            q("bool", QuestionType.YES_NO, null, 3, List.of("true")),
            new Question("grade", "Escala", trueQuestion(), true, null, 0, 10,
                2, List.of("8"))
        ));
        var validated = validator.validate(form, false, AUTO_EVALUATION);
        assertEquals(List.of("a", "b"), validated.questions().get(1).correctAnswers());
        assertEquals(4, validated.questions().size());
    }

    private QuestionType trueQuestion() {
        return QuestionType.SCALE;
    }

    @Test void refusesDuplicatesAndMissingOrInvalidChoiceKey() {
        invalid(new RecruitmentFormDefinition(List.of(
            q("x", QuestionType.SHORT_TEXT, null, null, null),
            q("x", QuestionType.SHORT_TEXT, null, null, null)
        )), false, HUMAN_EVALUATION);

        invalid(new RecruitmentFormDefinition(List.of(
            q("x", QuestionType.SINGLE_CHOICE, options(), 5, List.of("unknown"))
        )), false, AUTO_EVALUATION);

        invalid(new RecruitmentFormDefinition(List.of(
            q("x", QuestionType.MULTIPLE_CHOICE, options(), 5, List.of("a", "a"))
        )), false, AUTO_EVALUATION);
    }

    @Test void forbidsGabaritoAndPointsInHumanAndStaffForms() {
        invalid(new RecruitmentFormDefinition(List.of(
            q("secret", QuestionType.SINGLE_CHOICE, options(), 5, List.of("a"))
        )), true, AUTO_EVALUATION);
        invalid(new RecruitmentFormDefinition(List.of(
            q("secret", QuestionType.SINGLE_CHOICE, options(), 5, List.of("a"))
        )), false, HUMAN_EVALUATION);
    }

    @Test void refusesSubjectiveAutoExamAndMalformedNumbers() {
        invalid(null, false, AUTO_EVALUATION);
        invalid(new RecruitmentFormDefinition(List.of(
            q("essay", QuestionType.LONG_TEXT, null, 10, List.of("answer"))
        )), false, AUTO_EVALUATION);
        invalid(new RecruitmentFormDefinition(List.of(
            q("number", QuestionType.NUMBER, null, 10, List.of("NaN"))
        )), false, AUTO_EVALUATION);
        invalid(new RecruitmentFormDefinition(List.of(
            new Question("scale", "Nota", QuestionType.SCALE, true, null, 5, 1, 5, List.of("3"))
        )), false, AUTO_EVALUATION);
    }

    @Test void validatesOptionLimitsAndUniqueKeys() {
        invalid(new RecruitmentFormDefinition(List.of(
            q("bad", QuestionType.SINGLE_CHOICE, List.of(new Option("a", "A")), null, null)
        )), false, APPLICATION);
        invalid(new RecruitmentFormDefinition(List.of(
            q("bad", QuestionType.SINGLE_CHOICE,
                List.of(new Option("a", "A"), new Option("a", "B")), null, null)
        )), false, APPLICATION);
    }
}
