package com.Brafurries.API.user.recruitment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Separate projections intentionally: a submitted application is not a published
 * decision. In V1 all own applications report RECEIVED until publication of
 * results is implemented with explicit release authorization.
 */
public final class RecruitmentTrackingDtos {
    private RecruitmentTrackingDtos() {}

    public record OwnApplication(
        Long id,
        Long processId,
        String processTitle,
        Instant submittedAt,
        String publicStatus
    ) {}

    public record ReviewProcess(Long id, String title) {}

    public record ReviewQueueItem(
        Long id,
        Long processId,
        Long processVersionId,
        Integer candidateUserId,
        String processTitle,
        String internalStatus,
        Instant submittedAt
    ) {}

    public record ReviewStage(
        Long stageId,
        int position,
        String publicName,
        String stageType,
        String internalStatus,
        Instant enteredAt,
        Instant completedAt
    ) {}

    /** Review-only DTO; never reuse for candidate or public routes. */
    public record ReviewApplication(
        ReviewQueueItem application,
        List<ReviewStage> stages,
        Long submittedStageId,
        JsonNode candidateAnswers
    ) {}
}
