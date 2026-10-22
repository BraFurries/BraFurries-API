package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class RecruitmentStageEditorDtos {
    private RecruitmentStageEditorDtos() {}

    public record SaveStageRequest(
        @NotBlank @Size(max = 160) String internalName,
        @NotBlank @Size(max = 160) String publicName,
        @NotNull StageType stageType,
        RecruitmentFormDefinition candidateForm,
        RecruitmentFormDefinition staffForm
    ) {}

    /** Restricted admin response: includes private Staff form and answer keys. */
    public record StageDefinitionResponse(
        Long id, Long processId, Long versionId, int position,
        String internalName, String publicName, StageType stageType,
        RecruitmentFormDefinition candidateForm,
        RecruitmentFormDefinition staffForm
    ) {}

    public record ReorderStagesRequest(@NotNull List<@NotNull Long> stageIds) {}
}
