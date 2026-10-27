package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentExamReviewService.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Never mapped under candidate APIs; capability is enforced in the service. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/integrity-review")
public class RecruitmentExamReviewController {
    private final RecruitmentExamReviewService service;
    public RecruitmentExamReviewController(RecruitmentExamReviewService service) {
        this.service=service;
    }

    @GetMapping
    public ReviewPanel panel(Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId) {
        return service.panel(auth,communityId,applicationId,stageId);
    }

    @GetMapping("/attempts/{attemptId}/appeal")
    public CandidateAppealStatus candidateAppeal(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @PathVariable Long attemptId) {
        return service.candidateAppeal(auth,communityId,applicationId,stageId,attemptId);
    }

    /** Candidate-owned request, separately authenticated from Staff operations. */
    @PostMapping("/attempts/{attemptId}/appeal")
    public RecruitmentExamReviewRepository.Appeal appeal(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @PathVariable Long attemptId,@RequestBody ReasonRequest request) {
        return service.requestAppeal(auth,communityId,applicationId,stageId,attemptId,request);
    }

    @PostMapping("/attempts/{attemptId}/appeal/deny")
    public AppealDecision deny(Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @PathVariable Long attemptId,@RequestBody ReasonRequest request) {
        return service.denyAppeal(auth,communityId,applicationId,stageId,attemptId,request);
    }

    @PostMapping("/attempts/{attemptId}/reinstate")
    public Reinstatement reinstate(Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @PathVariable Long attemptId,@RequestBody ReasonRequest request) {
        return service.reinstate(auth,communityId,applicationId,stageId,attemptId,request);
    }
}
