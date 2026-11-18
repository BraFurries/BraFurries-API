package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.creatorstudio.CreatorStudioGlobalWorkflowService.*;
import com.Brafurries.API.creatorstudio.CreatorStudioProductionDtos.Workflow;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Platform owner publishes global process templates. An invited Pro artist
 * can only browse and explicitly copy a snapshot into their own catalog.
 */
@RestController
@RequestMapping("/user/me/creator-studio")
public class CreatorStudioGlobalWorkflowController {
    private final CreatorStudioGlobalWorkflowService library;

    public CreatorStudioGlobalWorkflowController(CreatorStudioGlobalWorkflowService library) {
        this.library = library;
    }

    @GetMapping("/global-workflows")
    public ResponseEntity<List<GlobalWorkflow>> published(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(library.published(auth));
    }

    @PostMapping("/workflows/from-global/{id}")
    public ResponseEntity<Workflow> importForArtist(Authentication auth,
            @PathVariable long id, @RequestBody(required = false) ImportDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(library.importAsPersonal(auth,id,input));
    }

    @GetMapping("/admin/global-workflows")
    public ResponseEntity<List<GlobalWorkflow>> administrative(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(library.administrative(auth));
    }

    @PostMapping("/admin/global-workflows")
    public ResponseEntity<GlobalWorkflow> create(Authentication auth,
            @RequestBody GlobalWorkflowDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(library.create(auth,input));
    }

    @PutMapping("/admin/global-workflows/{id}")
    public ResponseEntity<GlobalWorkflow> update(Authentication auth,
            @PathVariable long id, @RequestBody GlobalWorkflowDraft input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(library.update(auth,id,input));
    }
}
