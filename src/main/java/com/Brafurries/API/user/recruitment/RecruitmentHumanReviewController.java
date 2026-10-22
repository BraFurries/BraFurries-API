package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentHumanReviewService.*;

import com.Brafurries.API.user.recruitment.RecruitmentHumanReviewRepository.Assessment;
import com.Brafurries.API.user.recruitment.RecruitmentHumanReviewRepository.Assignment;
import com.Brafurries.API.user.recruitment.RecruitmentHumanReviewRepository.ReviewableStage;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Restricted internal API. Never exposed through candidate routes or Coddy.
 * Decisions, advancement, and publication are deliberately separate.
 */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment")
public class RecruitmentHumanReviewController {
    private final RecruitmentHumanReviewService human;

    public RecruitmentHumanReviewController(RecruitmentHumanReviewService human) {
        this.human = human;
    }

    @GetMapping("/processes/{processId}/reviewer-stages")
    public List<ReviewableStage> reviewableStages(
        Authentication auth, @PathVariable Integer communityId, @PathVariable Long processId
    ) {
        return human.stagesForProcess(auth, communityId, processId);
    }

    @GetMapping("/stages/{stageId}/reviewers")
    public List<Assignment> reviewers(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long stageId
    ) {
        return human.assignments(auth, communityId, stageId);
    }

    @PostMapping("/stages/{stageId}/reviewers")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentReceipt assign(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long stageId, @RequestBody AssignmentRequest request
    ) {
        return human.assign(auth, communityId, stageId, request);
    }

    @DeleteMapping("/stages/{stageId}/reviewers/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long stageId, @PathVariable Long assignmentId
    ) {
        human.revoke(auth, communityId, stageId, assignmentId);
    }

    @GetMapping("/applications/{applicationId}/stages/{stageId}/assessments/form")
    public RecruitmentFormDefinition assessmentForm(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long applicationId, @PathVariable Long stageId
    ) {
        return human.assessmentForm(auth, communityId, applicationId, stageId);
    }

    @PostMapping("/applications/{applicationId}/stages/{stageId}/assessments/mine")
    @ResponseStatus(HttpStatus.CREATED)
    public Assessment assess(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long applicationId, @PathVariable Long stageId,
        @RequestBody AssessmentRequest request
    ) {
        return human.assess(auth, communityId, applicationId, stageId, request);
    }

    @GetMapping("/applications/{applicationId}/stages/{stageId}/assessments/mine")
    public Assessment ownAssessment(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long applicationId, @PathVariable Long stageId
    ) {
        return human.ownAssessment(auth, communityId, applicationId, stageId);
    }
}
