package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeService.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Grade policies and score aggregates are never mapped on candidate routes. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment")
public class RecruitmentObjectiveGradeController {
    private final RecruitmentObjectiveGradeService service;
    public RecruitmentObjectiveGradeController(RecruitmentObjectiveGradeService service) {
        this.service=service;
    }

    @PutMapping("/processes/{processId}/stages/{stageId}/grade-policy")
    public Configured configure(Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long processId,@PathVariable Long stageId,
        @RequestBody ConfigureRequest request) {
        return service.configure(auth,communityId,processId,stageId,request);
    }

    @GetMapping("/applications/{applicationId}/stages/{stageId}/objective-grades")
    public GradeSummary grades(Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId) {
        return service.managerSummary(auth,communityId,applicationId,stageId);
    }
}
