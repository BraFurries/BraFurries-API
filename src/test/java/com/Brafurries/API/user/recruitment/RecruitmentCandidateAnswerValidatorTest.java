package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RecruitmentCandidateAnswerValidatorTest {
    final RecruitmentCandidateAnswerValidator validator = new RecruitmentCandidateAnswerValidator();
    final ObjectMapper json = new ObjectMapper();

    private JsonNode node(String value) {
        try { return json.readTree(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private RecruitmentFormDefinition form() {
        return new RecruitmentFormDefinition(List.of(
            new Question("bio", "Motivação", QuestionType.SHORT_TEXT, true,
                null,null,null,null,null),
            new Question("vote", "Escolha", QuestionType.SINGLE_CHOICE, true,
                List.of(new Option("a","A"),new Option("b","B")),null,null,null,null),
            new Question("skills", "Competências", QuestionType.MULTIPLE_CHOICE, true,
                List.of(new Option("x","X"),new Option("y","Y")),null,null,null,null),
            new Question("consent", "Aceite", QuestionType.CONSENT,true,
                null,null,null,null,null)
        ));
    }

    private Map<String,JsonNode> valid() {
        return Map.of("bio",node("\"Quero ajudar\""),"vote",node("\"a\""),
            "skills",node("[\"x\",\"y\"]"),"consent",node("true"));
    }

    private void invalid(Map<String,JsonNode> answers) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                validator.validate(form(),answers)).getStatusCode());
    }

    @Test void acceptsConfiguredAnswerTypes() {
        assertEquals(4,validator.validate(form(),valid()).size());
    }

    @Test void rejectsUnknownQuestionAndMissingRequiredAnswers() {
        var unknown = new java.util.HashMap<>(valid());
        unknown.put("foreign",node("\"anything\""));
        invalid(unknown);
        var missing = new java.util.HashMap<>(valid());
        missing.remove("bio");
        invalid(missing);
    }

    @Test void rejectsDuplicateOrAlienChoicesAndMissingConsent() {
        var wrong = new java.util.HashMap<>(valid());
        wrong.put("vote",node("\"not-an-option\""));
        invalid(wrong);
        wrong.put("vote",node("\"a\""));
        wrong.put("skills",node("[\"x\",\"x\"]"));
        invalid(wrong);
        wrong.put("skills",node("[\"x\"]"));
        wrong.put("consent",node("false"));
        invalid(wrong);
    }

    @Test void partialDraftAllowsMissingRequiredAndClearedAnswers() {
        var draft=validator.validatePartial(form(),Map.of(
            "vote",node("\\"b\\""),
            "skills",node("[]"),
            "bio",node("\\"   \\"")));
        assertEquals(1,draft.size());
        assertEquals("b",draft.get("vote").asText());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->validator.validate(form(),draft)).getStatusCode());
    }

    @Test void partialDraftStillRejectsInvalidOrUnknownFields() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->validator.validatePartial(form(),Map.of("vote",node("\\"other\\""))))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->validator.validatePartial(form(),Map.of("secret",node("null"))))
            .getStatusCode());
    }

    @Test void rejectsTypeMismatchAndExcessiveText() {
        var wrong = new java.util.HashMap<>(valid());
        wrong.put("bio",node("123"));
        invalid(wrong);
        wrong.put("bio",node("\""+("x".repeat(501))+"\""));
        invalid(wrong);
    }
}
