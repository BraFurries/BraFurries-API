package com.Brafurries.API.auth.social;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/account-link")
public class SocialAccountLinkController {
    private final SocialAccountLinkService service;

    public SocialAccountLinkController(SocialAccountLinkService service) {
        this.service = service;
    }

    public record StartLinkRequest(String frontendOrigin) {}

    @PostMapping("/discord/challenge")
    public SocialAccountLinkService.LinkUrl startDiscord(
        Authentication authentication,
        @RequestBody StartLinkRequest body
    ) {
        return service.issueChallenge(authentication, body == null ? null : body.frontendOrigin());
    }
}
