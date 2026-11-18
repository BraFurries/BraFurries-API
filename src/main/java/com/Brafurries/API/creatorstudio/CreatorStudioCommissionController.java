package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCommissionDtos.*;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * All routes require a currently valid PRO entitlement in the service.
 * A creator cannot request or select another user's private commission data.
 */
@RestController
@RequestMapping("/user/me/creator-studio/commissions")
public class CreatorStudioCommissionController {
    private final CreatorStudioCommissionService commissions;

    public CreatorStudioCommissionController(CreatorStudioCommissionService commissions) {
        this.commissions = commissions;
    }

    @GetMapping
    public ResponseEntity<CommissionPage> list(
        Authentication auth,
        @RequestParam(required = false) Status status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int pageSize,
        @RequestParam(required = false) Long workflowId,
        @RequestParam(required = false) Long stageDefinitionId,
        @RequestParam(defaultValue = "false") boolean unlinked
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commissions.filteredList(auth, status, page, pageSize,
                workflowId, stageDefinitionId, unlinked));
    }

    // Keep direct Java callers/tests of the original contract compatible.
    public ResponseEntity<CommissionPage> list(Authentication auth, Status status, int page,
                                                int pageSize) {
        return list(auth, status, page, pageSize, null, null, false);
    }

    @PostMapping
    public ResponseEntity<Commission> create(
        Authentication auth, @Valid @RequestBody SaveCommission request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(commissions.create(auth, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Commission> find(Authentication auth, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commissions.find(auth, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Commission> update(
        Authentication auth, @PathVariable long id,
        @Valid @RequestBody SaveCommission request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commissions.update(auth, id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Commission> changeStatus(
        Authentication auth, @PathVariable long id,
        @Valid @RequestBody ChangeStatus request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commissions.changeStatus(auth, id, request));
    }
}
