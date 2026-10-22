package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentCandidateDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Applicant-only routes, never share the administrator DTOs that include gabaritos. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/open")
public class RecruitmentCandidateController {
    private final RecruitmentCandidateService service;
    public RecruitmentCandidateController(RecruitmentCandidateService service) {
        this.service = service;
    }

    @GetMapping
    public List<OpenProcess> list(Authentication auth, @PathVariable Integer communityId) {
        return service.list(auth,communityId);
    }

    @GetMapping("/{processId}")
    public ApplicationForm form(
        Authentication auth,@PathVariable Integer communityId,@PathVariable Long processId
    ) {
        return service.form(auth,communityId,processId);
    }

    @PostMapping("/{processId}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public Applied apply(
        Authentication auth,@PathVariable Integer communityId,@PathVariable Long processId,
        @RequestBody @Valid ApplyRequest request
    ) {
        return service.apply(auth,communityId,processId,request);
    }
}

/** Opening enrollment is separately delegated to publish-capable Staff only. */
@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/intake")
class RecruitmentIntakeAdminController {
    private final RecruitmentCandidateService service;
    RecruitmentIntakeAdminController(RecruitmentCandidateService service) {
        this.service=service;
    }

    public record OpenEnrollmentRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotNull Instant endsAt
    ) {}

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(
        Authentication auth,@PathVariable Integer communityId,@PathVariable Long processId,
        @RequestBody @Valid OpenEnrollmentRequest request
    ) {
        service.open(auth,communityId,processId,request.expectedRevision(),request.endsAt());
    }
}
