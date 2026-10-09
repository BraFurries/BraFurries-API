package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityTeamDemandDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/team/demands")
public class CommunityTeamDemandCommunityController {
    private final CommunityTeamDemandService service;

    public CommunityTeamDemandCommunityController(CommunityTeamDemandService service) {
        this.service = service;
    }

    @GetMapping
    public List<DemandResponse> list(
        Authentication auth,
        @PathVariable Integer communityId
    ) {
        return service.listCommunity(auth, communityId);
    }

    @PostMapping
    public DemandResponse create(
        Authentication auth,
        @PathVariable Integer communityId,
        @RequestBody @Valid SaveDemandRequest request
    ) {
        return service.createCommunity(auth, communityId, request);
    }

    @PutMapping("/{demandId}")
    public DemandResponse update(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer demandId,
        @RequestBody @Valid SaveDemandRequest request
    ) {
        return service.updateCommunity(auth, communityId, demandId, request);
    }

    @DeleteMapping("/{demandId}")
    public void delete(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer demandId
    ) {
        service.deleteCommunity(auth, communityId, demandId);
    }
}
