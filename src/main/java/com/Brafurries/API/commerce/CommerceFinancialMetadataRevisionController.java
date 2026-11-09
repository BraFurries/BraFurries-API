package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Append-only metadata correction API: never updates payment/cash base tables. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/finance")
public class CommerceFinancialMetadataRevisionController {
    public record PaymentInput(
        @NotBlank @Size(max=120) String reference,
        @Size(max=5000) String notes,
        @NotBlank @Size(min=3,max=500) String reason
    ) {}
    public record CashInput(
        @NotBlank @Size(max=500) String description,
        @Size(max=120) String reference,
        @Size(max=5000) String notes,
        @NotBlank @Size(min=3,max=500) String reason
    ) {}
    private final CommerceFinancialMetadataRevisionService revisions;
    public CommerceFinancialMetadataRevisionController(
        CommerceFinancialMetadataRevisionService revisions) {
        this.revisions=revisions;
    }

    @PostMapping("/statements/{statementId}/payments/{paymentId}/metadata-corrections")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void payment(Authentication auth,@PathVariable long statementId,
                        @PathVariable long paymentId,
                        @Valid @RequestBody PaymentInput input) {
        revisions.revisePayment(auth,statementId,paymentId,
            new CommerceFinancialMetadataRevisionService.PaymentRevision(
                input.reference(),input.notes(),input.reason()));
    }

    @GetMapping("/statements/{statementId}/payments/{paymentId}/metadata-corrections")
    public List<Map<String,Object>> paymentHistory(
        Authentication auth,@PathVariable long statementId,@PathVariable long paymentId) {
        return revisions.paymentHistory(auth,statementId,paymentId);
    }

    @PostMapping("/cash-movements/{movementId}/metadata-corrections")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cash(Authentication auth,@PathVariable long movementId,
                     @Valid @RequestBody CashInput input) {
        revisions.reviseCash(auth,movementId,
            new CommerceFinancialMetadataRevisionService.CashRevision(
                input.description(),input.reference(),input.notes(),input.reason()));
    }

    @GetMapping("/cash-movements/{movementId}/metadata-corrections")
    public List<Map<String,Object>> cashHistory(Authentication auth,@PathVariable long movementId) {
        return revisions.cashHistory(auth,movementId);
    }
}
