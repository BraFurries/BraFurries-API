package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/team/roles")
public class CommunityTeamRoleController {
    private final CommunityTeamRoleService service;
    public CommunityTeamRoleController(CommunityTeamRoleService service) { this.service = service; }

    @GetMapping public List<RoleResponse> list(Authentication auth, @PathVariable String guildId) { return service.list(auth, guildId); }
    @PostMapping public RoleResponse create(Authentication auth, @PathVariable String guildId, @RequestBody @Valid CreateRoleRequest request) { return service.create(auth, guildId, request); }
    @PutMapping("/{roleId}") public RoleResponse update(Authentication auth, @PathVariable String guildId, @PathVariable Integer roleId, @RequestBody @Valid UpdateRoleRequest request) { return service.update(auth, guildId, roleId, request); }
    @PatchMapping("/{roleId}/active") public RoleResponse active(Authentication auth, @PathVariable String guildId, @PathVariable Integer roleId, @RequestBody @Valid UpdateRoleActiveRequest request) { return service.updateActive(auth, guildId, roleId, request); }
    @GetMapping("/{roleId}/deletion-impact") public RoleDeletionImpactResponse deletionImpact(Authentication auth, @PathVariable String guildId, @PathVariable Integer roleId) { return service.deletionImpact(auth, guildId, roleId); }
    @DeleteMapping("/{roleId}") public void delete(Authentication auth, @PathVariable String guildId, @PathVariable Integer roleId) { service.delete(auth, guildId, roleId); }
}
