package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixed BraFurries control plane and artist self-service. No Community selector
 * and no client-supplied account ID for financial reads.
 */
@RestController
@RequestMapping("/user/me/brafurries-commerce/artist")
public class CommerceArtistController {
    private final CommerceArtistService artists;

    public CommerceArtistController(CommerceArtistService artists) {
        this.artists=artists;
    }

    @GetMapping("/profiles")
    public List<Map<String,Object>> profiles(Authentication auth) {
        return artists.artists(auth);
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

    @GetMapping("/me/claims")
    public ResponseEntity<List<Map<String,Object>>> myClaims(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(artists.myClaims(auth));
    }

    @PostMapping("/me/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public Created requestClaim(Authentication auth,@Valid @RequestBody RequestClaim request) {
        return artists.requestClaim(auth,request);
    }

    @GetMapping("/claims/pending")
    public ResponseEntity<List<Map<String,Object>>> pending(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(artists.pendingClaims(auth));
    }

    @PostMapping("/claims/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth,@PathVariable long id,
                        @Valid @RequestBody ApproveClaim request) {
        artists.approve(auth,id,request);
    }

    @PostMapping("/claims/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(Authentication auth,@PathVariable long id,
                       @Valid @RequestBody RejectClaim request) {
        artists.reject(auth,id,request);
    }

    @PostMapping("/profiles/{artistId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth,@PathVariable long artistId,
                       @Valid @RequestBody RevokeClaim request) {
        artists.revoke(auth,artistId,request);
    }
}
