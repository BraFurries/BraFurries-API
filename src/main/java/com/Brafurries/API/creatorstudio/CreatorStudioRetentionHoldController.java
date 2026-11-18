package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.creatorstudio.CreatorStudioRetentionHoldService.Hold;
import com.Brafurries.API.creatorstudio.CreatorStudioRetentionHoldService.HoldDraft;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Management API: requireOwner in the service is the authorization boundary. */
@RestController
@RequestMapping("/user/me/creator-studio/admin/retention/holds")
public class CreatorStudioRetentionHoldController {
    private final CreatorStudioRetentionHoldService holds;

    public CreatorStudioRetentionHoldController(CreatorStudioRetentionHoldService holds) {
        this.holds=holds;
    }

    @GetMapping("/{artistUserId}/{commissionId}")
    public ResponseEntity<Hold> inspect(Authentication auth,
        @PathVariable int artistUserId, @PathVariable long commissionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(holds.inspect(auth,artistUserId,commissionId));
    }

    @PostMapping
    public ResponseEntity<Hold> place(Authentication auth,
                                      @Valid @RequestBody HoldDraft draft) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore()).body(holds.place(auth,draft));
    }

    @DeleteMapping("/{artistUserId}/{commissionId}")
    public ResponseEntity<Void> release(Authentication auth,
        @PathVariable int artistUserId, @PathVariable long commissionId) {
        holds.release(auth,artistUserId,commissionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
