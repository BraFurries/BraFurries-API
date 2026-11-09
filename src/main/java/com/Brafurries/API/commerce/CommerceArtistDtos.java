package com.Brafurries.API.commerce;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Never accept actor, owner or Community IDs from the browser. */
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

    public record RequestClaim(
        @NotNull @Min(1) Integer artistUserId,
        @Size(max = 1000) String message
    ) {}

    public record ApproveClaim(
        @NotBlank String verificationMethod,
        @NotBlank @Size(max = 1000) String verificationNotes
    ) {}

    public record RejectClaim(@NotBlank @Size(max = 1000) String reason) {}
    public record RevokeClaim(@NotBlank @Size(max = 1000) String reason) {}

    public record Portfolio(
        int year, int month,
        BigDecimal accruedMonth, BigDecimal accruedTotal,
        BigDecimal paidForPeriod, BigDecimal paidTotal,
        List<Map<String,Object>> artworks,
        List<Map<String,Object>> settlements
    ) {}
}
