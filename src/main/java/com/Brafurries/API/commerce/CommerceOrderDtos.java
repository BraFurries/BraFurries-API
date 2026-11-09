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

/** Operational orders, not financial sales or community virtual currency. */
public final class CommerceOrderDtos {
    private CommerceOrderDtos() {}

    public record Line(
        Long productId,
        @NotBlank @Size(max=180) String label,
        @Size(max=64) String size,
        @NotNull @Min(1) Integer quantity,
        @NotNull @DecimalMin("0") BigDecimal unitPrice
    ) {}

    public record Create(
        @NotBlank String requestKey,
        @NotBlank @Size(max=160) String buyerLabel,
        @NotBlank String channel,
        @Size(max=120) String externalReference,
        @NotNull LocalDate orderedOn,
        LocalDate expectedDeliveryOn,
        @Size(max=5000) String notes,
        @Valid @NotEmpty List<Line> items
    ) {}

    public record Transition(
        @NotBlank String status,
        LocalDate deliveredOn,
        @Size(max=500) String reason
    ) {}

    public record Receive(
        @NotBlank String requestKey,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate receivedOn,
        @NotBlank String paymentMethod,
        @Size(max=120) String externalReference,
        @Size(max=500) String notes
    ) {}

    public record Reverse(
        @NotBlank String requestKey,
        @NotNull LocalDate reversedOn,
        @NotBlank @Size(max=500) String reason
    ) {}

    public record Created(long id) {}
}
