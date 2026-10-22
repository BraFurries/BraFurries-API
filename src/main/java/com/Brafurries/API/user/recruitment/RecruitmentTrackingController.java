package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentTrackingDtos.*;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Candidate receipts never include internal stage states or published results. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications")
public class RecruitmentTrackingController {
    private final RecruitmentTrackingService tracking;

    public RecruitmentTrackingController(RecruitmentTrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/mine")
    public List<OwnApplication> own(
        Authentication auth,
        @PathVariable Integer communityId,
        @RequestParam(required = false) Long beforeId
    ) {
        return tracking.own(auth, communityId, beforeId);
    }

    @GetMapping("/mine/{applicationId}")
    public OwnApplication ownDetail(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Long applicationId
    ) {
        return tracking.ownDetail(auth, communityId, applicationId);
    }

    @GetMapping("/review")
    public List<ReviewQueueItem> reviewQueue(
        Authentication auth,
        @PathVariable Integer communityId,
        @RequestParam Long processId,
        @RequestParam(required = false) Long beforeId
    ) {
        return tracking.queue(auth, communityId, processId, beforeId);
    }

    @GetMapping("/review/{applicationId}")
    public ReviewApplication reviewDetail(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Long applicationId
    ) {
        return tracking.reviewDetail(auth, communityId, applicationId);
    }
}
