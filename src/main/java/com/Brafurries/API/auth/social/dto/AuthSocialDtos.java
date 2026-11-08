package com.Brafurries.API.auth.social.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class AuthSocialDtos {

    public record SocialLoginRequest(
        @Email String email,
        String providerToken,
        String displayName
    ) {
    }

    public record SocialRegisterRequest(
        @Email String email,
        String providerToken,
        String displayName,
        @NotNull @AssertTrue Boolean termsAgreement
    ) {
    }

    public record SocialRegistrationPreviewRequest(@NotBlank String registrationToken) {}

    public record CompleteSocialRegisterRequest(
        @NotBlank String registrationToken,
        @NotBlank @Size(max = 32) String displayName,
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotBlank String passwordConfirmation,
        @NotNull @AssertTrue Boolean termsAgreement
    ) {
    }

}
