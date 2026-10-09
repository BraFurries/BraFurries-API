package com.Brafurries.API.auth.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password,
        Boolean rememberMe
    ) {
    }

    public record RegisterRequest(
        @NotBlank @Size(max = 32) String displayName,
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotBlank String passwordConfirmation,
        @NotNull @AssertTrue Boolean termsAgreement
    ) {
    }

    public record RegisterResponse(
        Long id,
        String email,
        String message
    ) {
    }

    public record ConfirmEmailRequest(
        @NotBlank String token
    ) {
    }

    public record ConfirmEmailResponse(String message) {
    }

    public record RefreshTokenRequest(
        @JsonProperty("refresh_token") @NotBlank String refreshToken
    ) {
    }

    public record LogoutRequest(
        @JsonProperty("refresh_token") @NotBlank String refreshToken
    ) {
    }

    public record UserResponse(
        Long id,
        String name,
        String email,
        String profileImageUrl,
        List<String> roles,
        List<String> permissions
    ) {
    }

    public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn,
        UserResponse user
    ) {
    }

    public record RefreshTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn
    ) {
    }

    public record LogoutResponse(String message) {
    }


    public record ForgotPasswordRequest(
        @Email @NotBlank String email
    ) {
    }

    public record ForgotPasswordResponse(String message) {
    }

    public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank String password
    ) {
    }

    public record ResetPasswordResponse(String message) {
    }

    public record ClientTokenRequest(
        @JsonProperty("grant_type") @NotBlank String grantType,
        @JsonProperty("client_id") @NotBlank String clientId,
        @JsonProperty("client_secret") @NotBlank String clientSecret
    ) {
    }

    public record ClientTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn,
        String scope
    ) {
    }
}
