package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Financial data contracts are BraFurries-only; all money is BRL BigDecimal.
 * Request ids are client-generated UUIDs for safe retries after timeouts.
 */
public final class CommerceFinanceDtos {
    private CommerceFinanceDtos() {}

    public record SaleLine(
        @NotNull @Min(1) Long batchId,
        @NotNull @Min(1) Integer quantity,
        @NotNull @DecimalMin("0.00") BigDecimal unitPrice
    ) {}

    public record CreateSale(
        @NotBlank String requestKey,
        @NotBlank String channel,
        @Size(max=120) String externalReference,
        @Size(max=160) String buyerLabel,
        @NotNull LocalDate soldOn,
        @Size(max=5000) String notes,
        @Valid @NotEmpty List<SaleLine> items,
        String paymentMethod,
        @Size(max=120) String processingProvider,
        @DecimalMin("0") BigDecimal processingFeePercent,
        @DecimalMin("0") BigDecimal processingFeeFixed
    ) {
        // Legacy/test callers without payment details remain fee-free.
        public CreateSale(String requestKey,String channel,String externalReference,
                          String buyerLabel,LocalDate soldOn,String notes,List<SaleLine> items) {
            this(requestKey,channel,externalReference,buyerLabel,soldOn,notes,
                 items,"UNSPECIFIED",null,BigDecimal.ZERO,BigDecimal.ZERO);
        }
    }

    public record CancelSale(@NotBlank @Size(max=500) String reason) {}

    public record CreateStatement(
        @NotNull @Min(1) Integer artistUserId,
        @NotNull @Min(2000) Integer year,
        @NotNull @Min(1) Integer month
    ) {}

    public record RecordPayment(
        @NotBlank String requestKey,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate paidOn,
        @NotBlank @Size(max=120) String externalReference,
        @Size(max=5000) String notes
    ) {}

    public record ReversePayment(
        @NotNull LocalDate reversedOn,
        @NotBlank @Size(min=3,max=500) String reason
    ) {}

    public record Created(long id) {}
}
