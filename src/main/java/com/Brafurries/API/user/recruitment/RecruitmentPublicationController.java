package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentPublicationDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Administrative publication only. Does not expose application, answer keys,
 * candidate lists, or any route for opening enrollment.
 */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/publication")
public class RecruitmentPublicationController {
    private final RecruitmentPublicationService service;

    public RecruitmentPublicationController(RecruitmentPublicationService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public PublicationCheck preview(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Long processId
    ) {
        return service.preview(auth, communityId, processId);
    }

    @PutMapping("/eligibility")
    public PublicationCheck eligibility(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Long processId,
        @RequestBody @Valid UpdateEligibilityRequest request
    ) {
        return service.configureEligibility(auth, communityId, processId, request);
    }

    @PostMapping("/publish")
    public PublicationResult publish(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Long processId,
        @RequestBody @Valid PublishRequest request
    ) {
        return service.publish(auth, communityId, processId, request);
    }
}
