package com.Brafurries.API.auth.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link a Google subject using JWT session + independently verified Google ID
 * token. Never accepts a userId, e-mail or provider subject from the client.
 */
@RestController
@RequestMapping("/user/me/google-link")
public class GoogleSelfServiceLinkController {
    private final AuthSocialService social;

    public GoogleSelfServiceLinkController(AuthSocialService social) {
        this.social = social;
    }

    public record GoogleLinkRequest(@NotBlank String idToken) {}
    public record GoogleLinkResponse(String status) {}

    @PostMapping
    public GoogleLinkResponse link(Authentication authentication, @Valid @RequestBody GoogleLinkRequest request) {
        return new GoogleLinkResponse(social.linkGoogleToAuthenticatedAccount(authentication, request.idToken()));
    }
}
