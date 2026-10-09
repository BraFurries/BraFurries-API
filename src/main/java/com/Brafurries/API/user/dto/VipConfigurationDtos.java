package com.Brafurries.API.user.dto;

import com.Brafurries.API.user.dto.GuildManagementDtos.GuildRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class VipConfigurationDtos {
    private VipConfigurationDtos() {}

    public record VipConfigResponse(
        String guildId,
        List<String> roleIds,
        List<GuildRole> roles,
        String customRolePrefix,
        VipCustomRoleRange customRoleRange,
        boolean allowStaffColors,
        boolean runtimeSyncPending,
        List<String> warnings
    ) {}

    public record VipCustomRoleRange(String topRoleId, String bottomRoleId) {}

    public record VipConfigUpdateRequest(
        @NotNull List<
            @NotBlank
            @Pattern(regexp = "[1-9]\\d*", message = "roleId deve ser numérico e positivo")
            String
        > roleIds,
        @NotBlank @Size(max = 67) String customRolePrefix,
        @NotNull @Valid VipCustomRoleRangeRequest customRoleRange,
        @NotNull Boolean allowStaffColors
    ) {}

    public record VipCustomRoleRangeRequest(
        @Pattern(regexp = "[1-9]\\d*", message = "topRoleId deve ser numérico e positivo")
        String topRoleId,
        @Pattern(regexp = "[1-9]\\d*", message = "bottomRoleId deve ser numérico e positivo")
        String bottomRoleId
    ) {}
}
