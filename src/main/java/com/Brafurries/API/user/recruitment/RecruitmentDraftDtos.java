package com.Brafurries.API.user.recruitment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class RecruitmentDraftDtos {
    private RecruitmentDraftDtos() {}

    public record CreateProcessRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 10000) String description
    ) {}

    public record UpdateProcessRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 10000) String description
    ) {}

    public record ProcessResponse(
        Long id,
        Integer communityId,
        String title,
        String description,
        String status,
        String visibility,
        String enrollmentStatus,
        Integer draftVersion
    ) {}

    public enum StageType {
        APPLICATION, ANALYSIS, HUMAN_EVALUATION, AUTO_EVALUATION,
        INTERVIEW, DECISION, CUSTOM
    }

    public record CreateStageRequest(
        @NotBlank @Size(max = 160) String internalName,
        @NotBlank @Size(max = 160) String publicName,
        @NotNull StageType stageType
    ) {}

    public record StageResponse(
        Long id,
        Long processId,
        Long processVersionId,
        int position,
        String internalName,
        String publicName,
        StageType stageType
    ) {}

    public record ProcessDraftResponse(ProcessResponse process, List<StageResponse> stages) {}
}
