package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class CommunityMemberNoteDtos {
    private CommunityMemberNoteDtos() {}

    public record SaveCommunityMemberNoteRequest(
        @NotBlank
        @Size(max = 4000)
        String content
    ) {}

    public record CommunityMemberNoteResponse(
        Long id,
        Integer userId,
        Integer authorUserId,
        String authorDisplayName,
        Integer updatedByUserId,
        String updatedByDisplayName,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime archivedAt
    ) {}
}
