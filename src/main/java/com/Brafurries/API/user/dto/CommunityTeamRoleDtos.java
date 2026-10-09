package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class CommunityTeamRoleDtos {
    private CommunityTeamRoleDtos() { }

    public record RoleResponse(Integer id, String name, String description, Integer parentRoleId,
                               boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record CreateRoleRequest(@NotBlank @Size(max = 100) String name,
                                    @Size(max = 500) String description, Integer parentRoleId) { }
    public record UpdateRoleRequest(@NotBlank @Size(max = 100) String name,
                                    @Size(max = 500) String description, Integer parentRoleId) { }
    public record UpdateRoleActiveRequest(@NotNull Boolean active) { }
    public record RoleDeletionImpactResponse(boolean canDelete, long childRoleCount, long demandCount,
                                               long assignmentCount) { }
}
