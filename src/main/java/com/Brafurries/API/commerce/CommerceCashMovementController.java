package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceCashMovementDtos.*;

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

/** Financial cash movements are organization-private; never Community routed. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/finance/cash-movements")
public class CommerceCashMovementController {
    private final CommerceCashMovementService movements;

    public CommerceCashMovementController(CommerceCashMovementService movements) {
        this.movements=movements;
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,@RequestParam int year,
                                           @RequestParam int month) {
        return movements.list(auth,year,month);
    }

    @GetMapping("/summary")
    public Map<String,Object> summary(Authentication auth,@RequestParam int year,
                                      @RequestParam int month) {
        return movements.summary(auth,year,month);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(Authentication auth,@Valid @RequestBody CreateMovement input) {
        return movements.create(auth,input);
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Created reverse(Authentication auth,@PathVariable long id,
                           @Valid @RequestBody ReverseMovement input) {
        return movements.reverse(auth,id,input.reason());
    }
}
