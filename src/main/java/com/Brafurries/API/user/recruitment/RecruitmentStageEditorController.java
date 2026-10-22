package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentStageEditorDtos.*;

import com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/stages")
public class RecruitmentStageEditorController {
    private final RecruitmentStageEditorService service;

    public RecruitmentStageEditorController(RecruitmentStageEditorService service) {
        this.service = service;
    }

    /** Staff only: returns private gabarito and Staff form. */
    @GetMapping("/{stageId}/definition")
    public StageDefinitionResponse definition(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId, @PathVariable Long stageId
    ) {
        return service.detail(auth, communityId, processId, stageId);
    }

    @PutMapping("/{stageId}/definition")
    public StageDefinitionResponse update(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId, @PathVariable Long stageId,
        @RequestBody @Valid SaveStageRequest request
    ) {
        return service.update(auth, communityId, processId, stageId, request);
    }

    @PutMapping("/order")
    public List<StageResponse> order(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId, @RequestBody @Valid ReorderStagesRequest request
    ) {
        return service.reorder(auth, communityId, processId, request);
    }

    @DeleteMapping("/{stageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId, @PathVariable Long stageId
    ) {
        service.delete(auth, communityId, processId, stageId);
    }
}
