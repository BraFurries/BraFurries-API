package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityProvisioningDtos.*;

import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}")
@Tag(name = "Community Provisioning", description = "Canonical Community-scoped reads for access provisioning")
public class CommunityProvisioningReadController {
    private final CommunityProvisioningReadService service;

    public CommunityProvisioningReadController(CommunityProvisioningReadService service) {
        this.service = service;
    }

    @Operation(summary = "Lista cargos internos visíveis pela autorização da Community")
    @GetMapping("/team/roles")
    public List<RoleResponse> listRoles(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.listRoles(authentication, communityId);
    }

    @Operation(summary = "Lista resumos de membros visíveis pela autorização da Community")
    @GetMapping("/members")
    public CommunityMemberSummaryResponse listMembers(
        Authentication authentication,
        @PathVariable Integer communityId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String search
    ) {
        return service.listMembers(authentication, communityId, page, pageSize, search);
    }
}
