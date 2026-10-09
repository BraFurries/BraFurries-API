package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/team/roles")
public class CommunityTeamRoleCommunityController {
    private final CommunityTeamRoleService service;

    public CommunityTeamRoleCommunityController(CommunityTeamRoleService service) {
        this.service = service;
    }

    @PostMapping
    public RoleResponse create(
        Authentication auth,
        @PathVariable Integer communityId,
        @RequestBody @Valid CreateRoleRequest request
    ) {
        return service.createCommunity(auth, communityId, request);
    }

    @PutMapping("/{roleId}")
    public RoleResponse update(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @RequestBody @Valid UpdateRoleRequest request
    ) {
        return service.updateCommunity(auth, communityId, roleId, request);
    }

    @PatchMapping("/{roleId}/active")
    public RoleResponse active(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @RequestBody @Valid UpdateRoleActiveRequest request
    ) {
        return service.updateActiveCommunity(auth, communityId, roleId, request);
    }

    @GetMapping("/{roleId}/deletion-impact")
    public RoleDeletionImpactResponse deletionImpact(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId
    ) {
        return service.deletionImpactCommunity(auth, communityId, roleId);
    }

    @DeleteMapping("/{roleId}")
    public void delete(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId
    ) {
        service.deleteCommunity(auth, communityId, roleId);
    }
}
