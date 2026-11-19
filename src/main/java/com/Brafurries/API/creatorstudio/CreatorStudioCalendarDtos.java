package com.Brafurries.API.creatorstudio;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

public final class CreatorStudioCalendarDtos {
    private CreatorStudioCalendarDtos() {}

    public enum Kind { STEP, PRODUCTION, DELIVERY }
    public record Item(Kind kind, long commissionId, Long stepId, String title,
                       String clientName, String stepName, String commissionStatus,
                       String stepStatus, LocalDate date) {}
    public record Page(List<Item> items, boolean hasMore) {}
    // An explicit null removes a plan. Omission is malformed, not a deletion.
    public record Plan(@JsonProperty(value = "plannedOn", required = true) LocalDate plannedOn) {}
}
