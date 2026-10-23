package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionService.DecisionRequest;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionService.DecisionReceipt;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.Decision;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Internal only: decisions are deliberately absent from candidate-facing routes. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/decision")
public class RecruitmentStageDecisionController {
    private final RecruitmentStageDecisionService decisions;
    public RecruitmentStageDecisionController(RecruitmentStageDecisionService decisions) {
        this.decisions = decisions;
    }

    @GetMapping
    public Decision detail(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long applicationId, @PathVariable Long stageId
    ) {
        return decisions.find(auth,communityId,applicationId,stageId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionReceipt decide(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long applicationId, @PathVariable Long stageId,
        @RequestBody DecisionRequest request
    ) {
        return decisions.decide(auth,communityId,applicationId,stageId,request);
    }
}
