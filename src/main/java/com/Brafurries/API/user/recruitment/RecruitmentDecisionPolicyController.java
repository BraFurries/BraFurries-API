package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentDecisionPolicyService.SavePolicyRequest;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionPolicyService.StagePolicyResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Admin only. Frozen published versions are readable but never mutable. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/decision-policy")
public class RecruitmentDecisionPolicyController {
    private final RecruitmentDecisionPolicyService service;
    public RecruitmentDecisionPolicyController(RecruitmentDecisionPolicyService service) {
        this.service=service;
    }

    @GetMapping
    public StagePolicyResult detail(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long stageId
    ) {
        return service.detail(auth,communityId,processId,stageId);
    }

    @PutMapping
    public StagePolicyResult update(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long stageId,
        @RequestBody SavePolicyRequest request
    ) {
        return service.update(auth,communityId,processId,stageId,request);
    }
}
