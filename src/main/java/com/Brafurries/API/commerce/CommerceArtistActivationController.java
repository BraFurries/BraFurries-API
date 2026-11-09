package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public first registration is only possible with an issued invitation AND
 * its separately delivered confirmation code. Local email confirmation is
 * mandatory before any authenticated artist access.
 */
@RestController
@RequestMapping("/auth/artist-invitation")
public class CommerceArtistActivationController {
    private final CommerceArtistInvitationService invitations;

    public CommerceArtistActivationController(CommerceArtistInvitationService invitations) {
        this.invitations=invitations;
    }

    @PostMapping("/activate")
    public ResponseEntity<InvitationResult> activate(@Valid @RequestBody ActivateInvitation input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(invitations.activate(input));
    }
}
