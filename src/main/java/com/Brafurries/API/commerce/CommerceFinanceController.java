package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceFinanceDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Financial control plane, not under any selected Community route. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/finance")
public class CommerceFinanceController {
    private final CommerceSalesService sales;
    private final CommerceSettlementService settlements;

    public CommerceFinanceController(CommerceSalesService sales,CommerceSettlementService settlements) {
        this.sales=sales;
        this.settlements=settlements;
    }

    @GetMapping("/sales/available-batches")
    public List<Map<String,Object>> availableBatches(Authentication auth) {
        return sales.sellableBatches(auth);
    }

    @GetMapping("/sales")
    public List<Map<String,Object>> sales(Authentication auth) {
        return sales.listSales(auth);
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createSale(Authentication auth,@Valid @RequestBody CreateSale input) {
        return sales.createSale(auth,input);
    }

    @GetMapping("/sales/{saleId}/items")
    public List<Map<String,Object>> saleItems(Authentication auth,@PathVariable long saleId) {
        return sales.saleItems(auth,saleId);
    }

    @PostMapping("/sales/{saleId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSale(Authentication auth,@PathVariable long saleId,
                           @Valid @RequestBody CancelSale input) {
        sales.cancelSale(auth,saleId,input.reason());
    }

    @GetMapping("/royalties")
    public List<Map<String,Object>> royalties(Authentication auth,
                     @RequestParam int year,@RequestParam int month) {
        return sales.royalties(auth,year,month);
    }

    @GetMapping("/statements")
    public List<Map<String,Object>> statements(Authentication auth,
                     @RequestParam int year,@RequestParam int month) {
        return settlements.listStatements(auth,year,month);
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public Created draft(Authentication auth,@Valid @RequestBody CreateStatement input) {
        return settlements.createDraft(auth,input);
    }

    @GetMapping("/statements/{id}/items")
    public List<Map<String,Object>> statementItems(Authentication auth,@PathVariable long id) {
        return settlements.statementItems(auth,id);
    }

    @PostMapping("/statements/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth,@PathVariable long id) {
        settlements.approve(auth,id);
    }

    @PostMapping("/statements/{id}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(Authentication auth,@PathVariable long id) {
        settlements.discardDraft(auth,id);
    }

    @GetMapping("/statements/{id}/payments")
    public List<Map<String,Object>> payments(Authentication auth,@PathVariable long id) {
        return settlements.payments(auth,id);
    }

    @PostMapping("/statements/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Created recordPayment(Authentication auth,@PathVariable long id,
                                 @Valid @RequestBody RecordPayment input) {
        return settlements.recordPayment(auth,id,input);
    }

    @PostMapping("/statements/{id}/payments/{paymentId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Created reversePayment(Authentication auth,@PathVariable long id,
                                  @PathVariable long paymentId,
                                  @Valid @RequestBody ReversePayment input) {
        return settlements.reversePayment(auth,id,paymentId,input);
    }
}
