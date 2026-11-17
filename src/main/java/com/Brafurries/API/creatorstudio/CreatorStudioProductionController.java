package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioProductionDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Every catalog action checks an effective PRO entitlement and owner at service layer. */
@RestController
@RequestMapping("/user/me/creator-studio")
public class CreatorStudioProductionController {
    private final CreatorStudioCatalogService catalog;
    private final CreatorStudioStepService steps;

    public CreatorStudioProductionController(CreatorStudioCatalogService catalog,
                                             CreatorStudioStepService steps) {
        this.catalog = catalog;
        this.steps = steps;
    }

    @GetMapping("/art-types")
    public ResponseEntity<List<ArtType>> artTypes(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.artTypes(auth));
    }

    @PostMapping("/art-types")
    public ResponseEntity<ArtType> createArtType(Authentication auth,
                                                @Valid @RequestBody ArtTypeDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(catalog.createArtType(auth, input));
    }

    @PutMapping("/art-types/{id}")
    public ResponseEntity<ArtType> updateArtType(Authentication auth, @PathVariable long id,
                                                @Valid @RequestBody ArtTypeDraft input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(catalog.updateArtType(auth, id, input));
    }

    @GetMapping("/stage-definitions")
    public ResponseEntity<List<StageDefinition>> stageDefinitions(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(catalog.stageDefinitions(auth));
    }

    @PostMapping("/stage-definitions")
    public ResponseEntity<StageDefinition> createStageDefinition(Authentication auth,
                                            @Valid @RequestBody StageDefinitionDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(catalog.createStageDefinition(auth, input));
    }

    @GetMapping("/workflows")
    public ResponseEntity<List<Workflow>> workflows(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(catalog.workflows(auth));
    }

    @PostMapping("/workflows")
    public ResponseEntity<Workflow> createWorkflow(Authentication auth,
                                                   @Valid @RequestBody WorkflowDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(catalog.createWorkflow(auth, input));
    }

    @PutMapping("/workflows/{id}")
    public ResponseEntity<Workflow> updateWorkflow(Authentication auth, @PathVariable long id,
                                                   @Valid @RequestBody WorkflowDraft input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(catalog.updateWorkflow(auth, id, input));
    }

    @GetMapping("/commissions/{commissionId}/steps")
    public ResponseEntity<List<CommissionStep>> steps(Authentication auth,
                                                       @PathVariable long commissionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(steps.list(auth, commissionId));
    }

    @PostMapping("/commissions/{commissionId}/steps")
    public ResponseEntity<CommissionStep> addStep(Authentication auth, @PathVariable long commissionId,
                                                  @Valid @RequestBody StepDraft input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(steps.append(auth, commissionId, input));
    }

    @PostMapping("/commissions/{commissionId}/steps/from-workflow/{workflowId}")
    public ResponseEntity<List<CommissionStep>> copyWorkflow(Authentication auth,
                                    @PathVariable long commissionId, @PathVariable long workflowId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(steps.importWorkflow(auth, commissionId, workflowId));
    }

    @PutMapping("/commissions/{commissionId}/steps/{stepId}")
    public ResponseEntity<CommissionStep> updateStep(Authentication auth,
            @PathVariable long commissionId, @PathVariable long stepId,
            @Valid @RequestBody UpdateStep input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(steps.update(auth, commissionId, stepId, input));
    }

    @DeleteMapping("/commissions/{commissionId}/steps/{stepId}")
    public ResponseEntity<Void> deleteStep(Authentication auth, @PathVariable long commissionId,
                                           @PathVariable long stepId) {
        steps.delete(auth, commissionId, stepId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
