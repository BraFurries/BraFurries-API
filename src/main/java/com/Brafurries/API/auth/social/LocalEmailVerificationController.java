package com.Brafurries.API.auth.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/local-email-verification")
public class LocalEmailVerificationController {
    private final LocalEmailVerificationService verification;

    public LocalEmailVerificationController(LocalEmailVerificationService verification) {
        this.verification = verification;
    }

    public record VerifyRequest(@NotBlank @Email String email, @NotBlank String password) {}

    @GetMapping
    public LocalEmailVerificationService.Status status(Authentication auth) {
        return verification.status(auth);
    }

    @PostMapping
    public LocalEmailVerificationService.RequestResult request(
        Authentication auth, @Valid @RequestBody VerifyRequest input
    ) {
        return verification.request(auth, input.email(), input.password());
    }
}
