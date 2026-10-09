package com.Brafurries.API.user.dto;

import com.Brafurries.API.user.dto.GuildManagementDtos.StaffRolesResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ModerationAccessDtos {
    private ModerationAccessDtos() {}

    public record ModerationAccessResponse(
        String guildId,
        StaffRolesResponse staffRoles,
        CollaborativeModerationResponse collaborativeModeration
    ) {}

    public record CollaborativeModerationResponse(
        boolean enabled,
        String emoji,
        int minReactions,
        String participantMode,
        List<String> protectedStaffRoleIds
    ) {}

    public record CollaborativeModerationUpdateRequest(
        @NotNull Boolean enabled,
        @Size(max = 64) String emoji,
        @NotNull @Min(1) Integer minReactions
    ) {}

    public record PortariaBypassesResponse(
        boolean portariaEnabled,
        List<PortariaBypassResponse> bypasses
    ) {}

    public record PortariaBypassResponse(
        Long id,
        String type,
        String value,
        String accessMode,
        Boolean requiresForm,
        boolean active,
        boolean effective,
        Instant expiresAt,
        boolean expired,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PortariaBypassCreateRequest(
        @NotBlank String type,
        @NotBlank String value,
        String accessMode,
        Boolean requiresForm,
        Boolean active,
        Instant expiresAt
    ) {}

    public record PortariaBypassUpdateRequest(
        Boolean active,
        String accessMode,
        Boolean requiresForm,
        Instant expiresAt,
        Boolean clearExpiration
    ) {}
}
