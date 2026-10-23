package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentStageVotingService.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageVoteRepository.Ballot;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Ballot choices are private; only managers may see aggregate tally. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/votes")
public class RecruitmentStageVotingController {
    private final RecruitmentStageVotingService voting;
    public RecruitmentStageVotingController(RecruitmentStageVotingService voting) {
        this.voting=voting;
    }

    @GetMapping("/availability")
    public VotingAvailability availability(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) { return voting.availability(auth,communityId,applicationId,stageId); }

    @GetMapping("/mine")
    public Ballot mine(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) { return voting.mine(auth,communityId,applicationId,stageId); }

    @PostMapping("/mine")
    @ResponseStatus(HttpStatus.CREATED)
    public Ballot cast(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @RequestBody CastRequest request
    ) { return voting.cast(auth,communityId,applicationId,stageId,request); }

    @GetMapping("/tally")
    public VoteSummary tally(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId
    ) { return voting.tally(auth,communityId,applicationId,stageId); }

    @PostMapping("/finalize")
    public VotingDecision finalizeVote(
        Authentication auth,@PathVariable Integer communityId,
        @PathVariable Long applicationId,@PathVariable Long stageId,
        @RequestBody(required=false) TieOverride override
    ) { return voting.finalizeVote(auth,communityId,applicationId,stageId,override); }
}
