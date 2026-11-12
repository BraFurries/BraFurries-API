package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Legal identities are entered explicitly and are never inferred from OAuth,
 * Discord names, artistic aliases or a Community membership.
 */
public final class CommerceContractPreparationDtos {
    private CommerceContractPreparationDtos() {}

    public record ContractParty(
        @NotBlank @Size(max=180) String legalName,
        @NotBlank @Size(max=32) String document,
        @NotBlank @Size(max=500) String address,
        @Size(max=180) String representative
    ) {}

    public record PrepareContract(
        @Min(1) long templateId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        @NotNull @DecimalMin("0.00") BigDecimal oneTimeAmount,
        @Valid @NotNull ContractParty contractor,
        @Valid @NotNull ContractParty artist
    ) {}

    public record Created(long id) {}
}
