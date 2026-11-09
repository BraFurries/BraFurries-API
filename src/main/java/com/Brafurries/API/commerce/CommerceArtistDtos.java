package com.Brafurries.API.commerce;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class CommerceArtistDtos {
    private CommerceArtistDtos() {}

    public record Created(long id) {}

    public record RegisterExternalArtist(
        @NotBlank @Size(max = 120) String artistName,
        @Size(max = 100) String telegramHandle,
        @Size(max = 100) String instagramHandle,
        @Size(max = 255) String contactEmail,
        @Size(max = 5000) String internalNotes
    ) {}

    // Admin-only. Never accept an actor/Community ID from the browser.
    public record IssueInvitation(@NotNull @Min(1) Integer artistUserId) {}

    // Token and secret are only returned to the issuer once, never stored raw.
    public record IssuedInvitation(
        long id, String token, String confirmationCode, String expiresAt
    ) {}

    public record RedeemInvitation(
        @NotBlank String token,
        @NotBlank String confirmationCode,
        @NotBlank String accountPassword
    ) {}

    public record ActivateInvitation(
        @NotBlank String token,
        @NotBlank String confirmationCode,
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String passwordConfirmation,
        @NotNull Boolean termsAgreement
    ) {}

    public record InvitationResult(String status, String message) {}

    public record Portfolio(
        int year, int month,
        BigDecimal accruedMonth, BigDecimal accruedTotal,
        BigDecimal paidForPeriod, BigDecimal paidTotal,
        List<Map<String,Object>> artworks,
        List<Map<String,Object>> settlements
    ) {}
}
