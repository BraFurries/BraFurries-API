package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class CommunityTeamDemandDtos {
    private CommunityTeamDemandDtos() { }

    public record DemandResponse(Integer id, String name, String description, List<Integer> roleIds,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record SaveDemandRequest(@NotBlank @Size(max = 100) String name,
                                    @Size(max = 500) String description,
                                    @NotNull List<@NotNull Integer> roleIds) { }
}
