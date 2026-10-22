package com.Brafurries.API.user.recruitment;

import java.util.List;

/**
 * Typed draft-only editor schema. This model is INTERNAL: candidate-facing DTOs
 * must NEVER expose correctAnswers, points, or Staff form questions.
 */
public record RecruitmentFormDefinition(List<Question> questions) {
    public enum QuestionType {
        SHORT_TEXT, LONG_TEXT, SINGLE_CHOICE, MULTIPLE_CHOICE,
        YES_NO, NUMBER, SCALE, DATE, LINK, CONSENT
    }

    public record Option(String id, String label) {}

    public record Question(
        String id,
        String label,
        QuestionType type,
        boolean required,
        List<Option> options,
        Integer minValue,
        Integer maxValue,
        Integer points,
        List<String> correctAnswers
    ) {}
}
