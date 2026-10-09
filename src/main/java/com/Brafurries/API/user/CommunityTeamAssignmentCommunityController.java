package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.AssignmentResponse;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberRef;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberCandidateResponse;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/team")
public class CommunityTeamAssignmentCommunityController {
    private final CommunityTeamAssignmentService service;

    public CommunityTeamAssignmentCommunityController(
        CommunityTeamAssignmentService service
    ) {
        this.service = service;
    }

    @GetMapping("/roles/{roleId}/members")
    public List<TeamMemberRef> listMembers(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId
    ) {
        return service.listMembersCommunity(auth, communityId, roleId);
    }

    @GetMapping("/roles/{roleId}/member-candidates")
    public TeamMemberCandidateResponse listMemberCandidates(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String search
    ) {
        return service.listMemberCandidatesCommunity(
            auth,
            communityId,
            roleId,
            page,
            pageSize,
            search
        );
    }

    @PutMapping("/roles/{roleId}/members/{userId}")
    public AssignmentResponse assign(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @PathVariable Integer userId
    ) {
        return service.assignCommunity(auth, communityId, roleId, userId);
    }

    @DeleteMapping("/roles/{roleId}/members/{userId}")
    public void remove(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @PathVariable Integer userId
    ) {
        service.removeCommunity(auth, communityId, roleId, userId);
    }

    @GetMapping("/members/{userId}/roles")
    public List<RoleResponse> listRoles(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId
    ) {
        return service.listRolesCommunity(auth, communityId, userId);
    }
}
