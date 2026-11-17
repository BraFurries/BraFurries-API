package com.Brafurries.API.creatorstudio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Artist-entered data, never a verified client approval or automatic Discord event. */
public final class CreatorStudioDetailsDtos {
    private CreatorStudioDetailsDtos() {}

    public record ReferenceDraft(@Size(max = 120) String label,
                                 @NotBlank @Size(max = 1500) String url) {}

    public record Reference(long id, long commissionId, String label,
                            String url, int position) {}

    public enum ActivityKind {
        NOTE, REVISION_REQUEST, CHANGE_RECORDED, CLIENT_FEEDBACK,
        APPROVAL_REPORTED, DELIVERY_RECORDED
    }

    public record ActivityDraft(
        ActivityKind eventKind,
        @NotBlank @Size(max = 4000) String description,
        LocalDate occurredOn
    ) {}

    public record Activity(long id, long commissionId, ActivityKind eventKind,
                           String description, LocalDate occurredOn,
                           LocalDateTime createdAtUtc) {}

    public record ActivityPage(List<Activity> items, int page, int pageSize, long total) {}
}
