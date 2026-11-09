package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceOrderDtos.*;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operational preorder control plane, separate from confirmed sales. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/orders")
public class CommerceOrderController {
    private final CommerceOrderService orders;
    public CommerceOrderController(CommerceOrderService orders) {
        this.orders=orders;
    }

    @GetMapping("/product-choices")
    public List<Map<String,Object>> productChoices(Authentication auth) {
        return orders.productChoices(auth);
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth) {
        return orders.list(auth);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(Authentication auth,@Valid @RequestBody Create input) {
        return orders.create(auth,input);
    }
    @GetMapping("/{id}/items")
    public List<Map<String,Object>> items(Authentication auth,@PathVariable long id) {
        return orders.items(auth,id);
    }
    @PostMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transition(Authentication auth,@PathVariable long id,
                           @Valid @RequestBody Transition input) {
        orders.transition(auth,id,input);
    }
    @GetMapping("/{id}/payments")
    public List<Map<String,Object>> payments(Authentication auth,@PathVariable long id) {
        return orders.payments(auth,id);
    }
    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Created receive(Authentication auth,@PathVariable long id,
                           @Valid @RequestBody Receive input) {
        return orders.receive(auth,id,input);
    }
    @PostMapping("/{id}/payments/{receiptId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Created reverse(Authentication auth,@PathVariable long id,
                           @PathVariable long receiptId,@Valid @RequestBody Reverse input) {
        return orders.reverse(auth,id,receiptId,input);
    }
}
