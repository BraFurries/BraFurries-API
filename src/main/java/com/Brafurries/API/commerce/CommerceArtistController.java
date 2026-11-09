package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * BraFurries-only control plane and canonical artist self-service.
 * No anonymous claim by ID, no public artwork ownership endpoint.
 */
@RestController
@RequestMapping("/user/me/brafurries-commerce/artist")
public class CommerceArtistController {
    private final CommerceArtistService artists;
    private final CommerceArtistInvitationService invitations;

    public CommerceArtistController(CommerceArtistService artists,
                                    CommerceArtistInvitationService invitations) {
        this.artists=artists;
        this.invitations=invitations;
    }

    @GetMapping("/profiles")
    public ResponseEntity<List<Map<String,Object>>> profiles(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(artists.artists(auth));
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public Created registerExternal(Authentication auth,
                                    @Valid @RequestBody RegisterExternalArtist request) {
        return artists.registerExternal(auth,request);
    }

    @GetMapping("/me/portfolio")
    public ResponseEntity<Portfolio> portfolio(Authentication auth,
                                                @RequestParam int year,
                                                @RequestParam int month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(artists.portfolio(auth,year,month));
    }

    @PostMapping("/invitations")
    public ResponseEntity<IssuedInvitation> issue(Authentication auth,
                                                   @Valid @RequestBody IssueInvitation request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(invitations.issue(auth,request));
    }

    @PostMapping("/invitations/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth,@PathVariable long id) {
        invitations.revoke(auth,id);
    }

    @PostMapping("/invitations/redeem")
    public ResponseEntity<InvitationResult> redeem(Authentication auth,
        @Valid @RequestBody RedeemInvitation input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(invitations.redeem(auth,input));
    }
}
