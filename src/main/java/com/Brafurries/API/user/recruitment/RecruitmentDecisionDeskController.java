package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentDecisionDeskRepository.QueueItem;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionDeskService.*;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** No candidate/reviewer access. Decisions still go through the independent guarded API. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/decisions")
public class RecruitmentDecisionDeskController {
    private final RecruitmentDecisionDeskService service;
    public RecruitmentDecisionDeskController(RecruitmentDecisionDeskService service) {
        this.service=service;
    }

    @GetMapping("/queue")
    public List<QueueItem> queue(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@RequestParam(required=false) Long beforeId
    ) {
        return service.queue(auth,communityId,processId,beforeId);
    }

    @GetMapping("/applications/{applicationId}")
    public ApplicationDetail detail(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long applicationId
    ) {
        return service.detail(auth,communityId,processId,applicationId);
    }

    @GetMapping("/applications/{applicationId}/stages/{stageId}/evidence")
    public StageEvidence evidence(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long applicationId,
        @PathVariable Long stageId
    ) {
        return service.evidence(auth,communityId,processId,applicationId,stageId);
    }
}
