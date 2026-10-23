package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentObjectiveStageFinalizationService.Finalization;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Only managers may close an objective exam; never a candidate-facing endpoint. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/objective-finalization")
public class RecruitmentObjectiveStageFinalizationController {
    private final RecruitmentObjectiveStageFinalizationService finalizer;
    public RecruitmentObjectiveStageFinalizationController(
        RecruitmentObjectiveStageFinalizationService finalizer
    ) { this.finalizer=finalizer; }

    @PostMapping
    public Finalization finalizeExam(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) {return finalizer.finalizeStage(auth,communityId,applicationId,stageId);}
}
