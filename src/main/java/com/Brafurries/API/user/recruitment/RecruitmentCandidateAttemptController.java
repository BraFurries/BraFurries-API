package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptService.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Candidate routes are own-application only and never expose the answer key. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/attempts")
public class RecruitmentCandidateAttemptController {
    private final RecruitmentCandidateAttemptService service;
    public RecruitmentCandidateAttemptController(RecruitmentCandidateAttemptService service) {
        this.service=service;
    }
    @GetMapping("/scores")
    public java.util.List<PublicScore> scores(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) { return service.ownScores(auth,communityId,applicationId,stageId); }

    @PostMapping("/start")
    public AttemptSession start(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) { return service.start(auth,communityId,applicationId,stageId); }

    @PostMapping("/{attemptId}/submit")
    public Submission submit(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @PathVariable Long attemptId,@RequestBody SubmitRequest request
    ) { return service.submit(auth,communityId,applicationId,stageId,attemptId,request); }
}

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/exams")
class RecruitmentCandidateAvailableExamController {
    private final RecruitmentCandidateAttemptService service;
    RecruitmentCandidateAvailableExamController(RecruitmentCandidateAttemptService service) {
        this.service=service;
    }
    @GetMapping
    public java.util.List<RecruitmentCandidateAttemptRepository.AvailableExam> available(
        Authentication auth,@PathVariable Integer communityId,@PathVariable Long applicationId
    ) { return service.availableExams(auth,communityId,applicationId); }
}

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/attempt-policy")
class RecruitmentCandidateAttemptPolicyController {
    private final RecruitmentCandidateAttemptService service;
    RecruitmentCandidateAttemptPolicyController(RecruitmentCandidateAttemptService service) {
        this.service=service;
    }
    @GetMapping
    public PolicyResponse policy(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long stageId
    ) { return service.policy(auth,communityId,processId,stageId); }

    @PutMapping
    public PolicyResponse configure(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long stageId,
        @RequestBody ConfigureRequest request
    ) { return service.configure(auth,communityId,processId,stageId,request); }
}
