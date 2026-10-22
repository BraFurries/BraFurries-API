package com.Brafurries.API.user.recruitment;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RecruitmentCandidateDtos {
    private RecruitmentCandidateDtos() {}

    /** Candidate-safe projection; excludes points, gabaritos and staff forms. */
    public record PublicOption(String id, String label) {}
    public record PublicQuestion(
        String id, String label, String type, boolean required,
        List<PublicOption> options, Integer minValue, Integer maxValue
    ) {}
    public record PublicForm(List<PublicQuestion> questions) {}

    public record OpenProcess(
        Long id, String title, String description, Instant enrollmentEndsAt
    ) {}
    public record ApplicationForm(
        OpenProcess process, Long publishedVersionId, Long firstStageId,
        PublicForm form, String termsText, String termsHash,
        boolean eligible, List<String> eligibilityBlockers
    ) {}

    public record ApplyRequest(
        @NotBlank String termsHash,
        @NotNull Map<String, JsonNode> answers
    ) {}
    public record Applied(
        Long applicationId, Long publishedVersionId,
        String status, Instant submittedAt
    ) {}
}
