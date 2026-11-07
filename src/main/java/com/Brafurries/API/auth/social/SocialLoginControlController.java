package com.Brafurries.API.auth.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A disabled provider login never removes a Community identity. */
@RestController
@RequestMapping("/user/me/social-logins")
public class SocialLoginControlController {
    private final SocialLoginControlService controls;
    private final SocialLoginOAuthProofService oauthProofs;

    public SocialLoginControlController(
        SocialLoginControlService controls, SocialLoginOAuthProofService oauthProofs
    ) {
        this.controls = controls;
        this.oauthProofs = oauthProofs;
    }

    public record PasswordConfirmation(@NotBlank String password) {}
    public record GoogleEnableRequest(@NotBlank String password, @NotBlank String idToken) {}
    public record GoogleEnableOAuthRequest(@NotBlank String password, @NotBlank String ticket) {}
    public record DiscordEnableRequest(@NotBlank String password, @NotBlank String ticket) {}

    @GetMapping
    public List<SocialLoginControlService.ProviderLogin> list(Authentication auth) {
        return controls.list(auth);
    }

    @PostMapping("/{identityId}/disable")
    public SocialLoginControlService.ChangeResult disable(
        Authentication auth, @PathVariable Long identityId,
        @Valid @RequestBody PasswordConfirmation request
    ) {
        return controls.disable(auth, identityId, request.password());
    }

    @PostMapping("/google/start")
    public SocialLoginOAuthProofService.Start beginGoogle(Authentication authentication) {
        return oauthProofs.beginGoogle(authentication);
    }

    @PostMapping("/google/enable-oauth")
    public SocialLoginControlService.ChangeResult enableGoogleWithOAuth(
        Authentication authentication, @Valid @RequestBody GoogleEnableOAuthRequest request
    ) {
        return controls.enableGoogleOAuth(authentication, request.ticket(), request.password());
    }

    @PostMapping("/google/enable")
    public SocialLoginControlService.ChangeResult enableGoogle(
        Authentication auth, @Valid @RequestBody GoogleEnableRequest request
    ) {
        return controls.enableGoogle(auth, request.idToken(), request.password());
    }

    @PostMapping("/discord/enable")
    public SocialLoginControlService.ChangeResult enableDiscord(
        Authentication auth, @Valid @RequestBody DiscordEnableRequest request
    ) {
        return controls.enableDiscord(auth, request.ticket(), request.password());
    }
}
