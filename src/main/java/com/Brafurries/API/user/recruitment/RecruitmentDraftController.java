package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes")
public class RecruitmentDraftController {
    private final RecruitmentDraftService service;

    public RecruitmentDraftController(RecruitmentDraftService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProcessResponse> list(
        Authentication auth, @PathVariable Integer communityId
    ) {
        return service.list(auth, communityId);
    }

    @GetMapping("/{processId}")
    public ProcessDraftResponse detail(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId
    ) {
        return service.detail(auth, communityId, processId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessResponse create(
        Authentication auth, @PathVariable Integer communityId,
        @RequestBody @Valid CreateProcessRequest request
    ) {
        return service.create(auth, communityId, request);
    }

    @PutMapping("/{processId}")
    public ProcessResponse update(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId,
        @RequestBody @Valid UpdateProcessRequest request
    ) {
        return service.update(auth, communityId, processId, request);
    }

    @PostMapping("/{processId}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    public StageResponse appendStage(
        Authentication auth, @PathVariable Integer communityId,
        @PathVariable Long processId,
        @RequestBody @Valid CreateStageRequest request
    ) {
        return service.appendStage(auth, communityId, processId, request);
    }
}
