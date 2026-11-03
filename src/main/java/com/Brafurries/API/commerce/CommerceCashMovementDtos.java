package com.Brafurries.API.commerce;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Cash movements are separate from gross sales, artist obligations and transfers. */
public final class CommerceCashMovementDtos {
    private CommerceCashMovementDtos() {}

    public record CreateMovement(
        @NotBlank String requestKey,
        @NotBlank String movementType,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate occurredOn,
        @NotBlank @Size(max=80) String category,
        @NotBlank @Size(max=500) String description,
        @NotBlank @Size(max=160) String counterpartyLabel,
        Integer counterpartyUserId,
        String contributorType,
        String contributionNature,
        @NotBlank String paymentMethod,
        @Size(max=120) String externalReference,
        @Size(max=5000) String notes
    ) {}

    public record ReverseMovement(
        @NotBlank @Size(max=500) String reason
    ) {}

    public record Created(long id) {}
}
