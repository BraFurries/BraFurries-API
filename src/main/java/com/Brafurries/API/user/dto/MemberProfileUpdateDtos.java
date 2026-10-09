package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotBlank;

public final class MemberProfileUpdateDtos {

    private MemberProfileUpdateDtos() {
    }

    public record UpdateMemberProfileRequest(
        @NotBlank String displayName
    ) {
    }

    public record UpdateMemberProfileResponse(
        Integer userId,
        String displayName
    ) {
    }
}
