package com.Brafurries.API.commerce;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Contracts are commercial (BRL); no Community ID or authorization sent from the UI. */
public final class CommerceCatalogDtos {
    private CommerceCatalogDtos() {}

    public record CreateTemplate(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 5000) String description,
        @NotBlank String modality,
        @NotBlank String royaltyMethod,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal royaltyPercent,
        @DecimalMin("0.0") BigDecimal royaltyFixedAmount
    ) {}

    public record CreateArtwork(
        @NotBlank @Size(max = 180) String title,
        @Size(max = 5000) String description,
        @NotNull @Min(1) Integer artistUserId,
        @NotNull @DecimalMin("0.0") BigDecimal creationCost
    ) {}

    public record CreateSupplier(
        @NotBlank @Size(max = 180) String name,
        @Size(max = 140) String contactName,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String phone,
        @Size(max = 5000) String notes
    ) {}

    public record CreateProduct(
        @NotBlank @Size(max = 80) String sku,
        @NotBlank @Size(max = 180) String name,
        @Size(max = 5000) String description,
        @NotNull @DecimalMin("0.0") BigDecimal estimatedUnitCost,
        @NotNull @DecimalMin("0.0") BigDecimal suggestedPrice,
        Long preferredSupplierId
    ) {}

    public record LinkArtwork(
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal royaltyAllocationPercent
    ) {}

    public record CreateProductionBatch(
        @NotNull @Min(1) Long productId,
        @NotNull @Min(1) Long supplierId,
        @NotNull @Min(1) @Max(10000000) Integer quantity,
        @NotNull @DecimalMin("0.0") BigDecimal unitCost,
        @NotNull @DecimalMin("0.0") BigDecimal additionalCost,
        @NotNull LocalDate producedOn,
        @Size(max = 160) String reference
    ) {}

    public record Created(long id) {}
}
