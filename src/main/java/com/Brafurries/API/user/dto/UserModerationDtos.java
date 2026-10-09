package com.Brafurries.API.user.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class UserModerationDtos {
    private UserModerationDtos() {}

    public record MemberModerationPage(
        List<MemberModerationRecord> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
    ) {}

    public record MemberModerationRecord(
        Integer id,
        String type,
        String reason,
        LocalDate occurredAt,
        String status,
        Boolean canAppeal,
        LocalDate validUntil,
        LocalDateTime revokedAt,
        String revocationReason
    ) {}
}
