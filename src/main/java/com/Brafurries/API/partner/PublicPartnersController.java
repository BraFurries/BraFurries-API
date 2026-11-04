package com.Brafurries.API.partner;

import com.Brafurries.API.partner.PublicPartnersService.PublicPartnerItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe public homepage partners; admin editing stays behind /admin/**. */
@RestController
@RequestMapping("/public/partners")
public class PublicPartnersController {
    private final PublicPartnersService service;

    public PublicPartnersController(PublicPartnersService service) {
        this.service = service;
    }

    @GetMapping
    public List<PublicPartnerItem> list() {
        return service.listActive();
    }
}
