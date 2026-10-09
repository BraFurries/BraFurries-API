package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMember;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.AssignmentResponse;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/team")
public class CommunityTeamAssignmentController {
    private final CommunityTeamAssignmentService service;

    public CommunityTeamAssignmentController(CommunityTeamAssignmentService service) {
        this.service = service;
    }

    @GetMapping("/roles/{roleId}/members")
    public List<CommunityMember> listMembers(
        Authentication auth,
        @PathVariable String guildId,
        @PathVariable Integer roleId
    ) {
        return service.listMembers(auth, guildId, roleId);
    }

    @PutMapping("/roles/{roleId}/members/{userId}")
    public AssignmentResponse assign(
        Authentication auth,
        @PathVariable String guildId,
        @PathVariable Integer roleId,
        @PathVariable Integer userId
    ) {
        return service.assign(auth, guildId, roleId, userId);
    }

    @DeleteMapping("/roles/{roleId}/members/{userId}")
    public void remove(
        Authentication auth,
        @PathVariable String guildId,
        @PathVariable Integer roleId,
        @PathVariable Integer userId
    ) {
        service.remove(auth, guildId, roleId, userId);
    }

    @GetMapping("/members/{userId}/roles")
    public List<RoleResponse> listRoles(
        Authentication auth,
        @PathVariable String guildId,
        @PathVariable Integer userId
    ) {
        return service.listRoles(auth, guildId, userId);
    }
}
