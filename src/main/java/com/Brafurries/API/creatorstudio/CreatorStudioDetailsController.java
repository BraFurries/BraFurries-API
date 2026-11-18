package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioDetailsDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Private Pro-owned links and manual journal; browser may not choose owner ID. */
@RestController
@RequestMapping("/user/me/creator-studio/commissions/{commissionId}")
public class CreatorStudioDetailsController {
    private final CreatorStudioReferenceService references;
    private final CreatorStudioActivityService activity;

    public CreatorStudioDetailsController(CreatorStudioReferenceService references,
                                          CreatorStudioActivityService activity) {
        this.references = references;
        this.activity = activity;
    }

    @GetMapping("/references")
    public ResponseEntity<List<Reference>> references(Authentication auth,
                                                       @PathVariable long commissionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(references.list(auth, commissionId));
    }

    @PostMapping("/references")
    public ResponseEntity<Reference> addReference(Authentication auth,
              @PathVariable long commissionId, @Valid @RequestBody ReferenceDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(references.create(auth, commissionId, input));
    }

    @PutMapping("/references/{referenceId}")
    public ResponseEntity<Reference> updateReference(Authentication auth,
              @PathVariable long commissionId, @PathVariable long referenceId,
              @Valid @RequestBody ReferenceDraft input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(references.update(auth, commissionId, referenceId, input));
    }

    @DeleteMapping("/references/{referenceId}")
    public ResponseEntity<Void> deleteReference(Authentication auth,
              @PathVariable long commissionId, @PathVariable long referenceId) {
        references.delete(auth, commissionId, referenceId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/activity")
    public ResponseEntity<ActivityPage> history(Authentication auth,
              @PathVariable long commissionId,
              @RequestParam(defaultValue = "1") int page,
              @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(activity.list(auth, commissionId, page, pageSize));
    }

    @PostMapping("/activity")
    public ResponseEntity<Activity> addActivity(Authentication auth,
              @PathVariable long commissionId, @Valid @RequestBody ActivityDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(activity.create(auth, commissionId, input));
    }
}
