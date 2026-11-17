package com.Brafurries.API.creatorstudio;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** All inputs are scoped to the signed-in artist. No owner/community ID in DTOs. */
public final class CreatorStudioCommissionDtos {
    private CreatorStudioCommissionDtos() {}

    public enum Status {
        QUEUED, IN_PROGRESS, WAITING, PAUSED, COMPLETED
    }

    public record SaveCommission(
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 120) String clientName,
        @Size(max = 8000) String description,
        @Size(max = 8000) String privateNotes,
        @NotNull LocalDate requestedOn,
        LocalDate dueOn,
        @Min(1) Long artTypeId,
        @Min(1) Long workflowId
    ) {
        // Keep the existing 7-argument constructor for tests/callers.
        public SaveCommission(String title, String clientName, String description,
                              String privateNotes, LocalDate requestedOn,
                              LocalDate dueOn, Long artTypeId) {
            this(title, clientName, description, privateNotes, requestedOn,
                 dueOn, artTypeId, null);
        }
    }

    public record ChangeStatus(@NotNull Status status) {}

    public record Commission(
        long id,
        String title,
        String clientName,
        String description,
        String privateNotes,
        LocalDate requestedOn,
        LocalDate dueOn,
        Long artTypeId,
        Status status,
        LocalDateTime createdAtUtc,
        LocalDateTime updatedAtUtc,
        LocalDateTime completedAtUtc,
        String currentStageName,
        Long currentStageDefinitionId
    ) {}

    public record CommissionPage(List<Commission> items, int page, int pageSize, long total) {}
}
