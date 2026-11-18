package com.Brafurries.API.creatorstudio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Production metadata is private to the authenticated artist. The reusable
 * definition is optional; the copied step name belongs to its commission.
 */
public final class CreatorStudioProductionDtos {
    private CreatorStudioProductionDtos() {}

    public record ArtTypeDraft(@NotBlank @Size(max = 100) String name, Long workflowId) {}
    public record ArtType(long id, String name, Long workflowId) {}

    public record StageDefinitionDraft(@NotBlank @Size(max = 100) String name) {}
    public record StageDefinition(long id, String name) {}

    /**
     * reuse=true opts into the artist's shared stage library. Absent/false
     * preserves the old behavior for existing callers and custom-only steps.
     */
    public record StepDraft(Long stageDefinitionId, @NotBlank @Size(max = 100) String name,
                            Boolean reuse) {
        public StepDraft(Long stageDefinitionId, String name) {
            this(stageDefinitionId, name, null);
        }
    }

    public record WorkflowDraft(
        @NotBlank @Size(max = 100) String name,
        boolean isDefault,
        @NotNull @Size(max = 40) List<@Valid StepDraft> steps
    ) {}

    public record WorkflowStep(long id, String name, Long stageDefinitionId, int position) {}
    public record Workflow(long id, String name, boolean isDefault, List<WorkflowStep> steps) {}

    public enum StepStatus { PENDING, IN_PROGRESS, WAITING_APPROVAL, DONE }
    public record UpdateStep(
        Long stageDefinitionId,
        @NotBlank @Size(max = 100) String name,
        @NotNull StepStatus status,
        @Size(max = 8000) String notes
    ) {}
    public record CommissionStep(
        long id, long commissionId, String name, Long stageDefinitionId,
        int position, StepStatus status, String notes, LocalDateTime completedAtUtc
    ) {}

    /** A commission step ID, not a global/template stage or browser-supplied owner ID. */
    public record MoveToStep(@jakarta.validation.constraints.Min(1) long stepId) {}
}
