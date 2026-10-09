package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityCapabilityManagementDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}")
@Tag(name = "Community Capabilities", description = "Owner/Admin capability provisioning")
public class CommunityCapabilityManagementController {
    private final CommunityCapabilityManagementService service;

    public CommunityCapabilityManagementController(CommunityCapabilityManagementService service) {
        this.service = service;
    }

    @Operation(summary = "Lista o catálogo canônico de capabilities da Community")
    @GetMapping("/capabilities/catalog")
    public CapabilityCatalogResponse catalog(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.catalog(authentication, communityId);
    }

    @Operation(summary = "Lista grants diretos de um membro")
    @GetMapping("/members/{userId}/capabilities")
    public DirectCapabilityGrantsResponse directGrants(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer userId
    ) {
        return service.directGrants(authentication, communityId, userId);
    }

    @Operation(summary = "Concede uma capability direta a um membro")
    @PutMapping("/members/{userId}/capabilities/{capability}")
    public DirectCapabilityGrantsResponse grantDirect(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @PathVariable String capability
    ) {
        return service.grantDirect(authentication, communityId, userId, capability);
    }

    @Operation(summary = "Revoga uma capability direta de um membro")
    @DeleteMapping("/members/{userId}/capabilities/{capability}")
    public DirectCapabilityGrantsResponse revokeDirect(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer userId,
        @PathVariable String capability
    ) {
        return service.revokeDirect(authentication, communityId, userId, capability);
    }

    @Operation(summary = "Lista capabilities configuradas em um cargo interno")
    @GetMapping("/team/roles/{roleId}/capabilities")
    public RoleCapabilityGrantsResponse roleGrants(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId
    ) {
        return service.roleGrants(authentication, communityId, roleId);
    }

    @Operation(summary = "Concede uma capability a um cargo interno")
    @PutMapping("/team/roles/{roleId}/capabilities/{capability}")
    public RoleCapabilityGrantsResponse grantRole(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @PathVariable String capability
    ) {
        return service.grantRole(authentication, communityId, roleId, capability);
    }

    @Operation(summary = "Revoga uma capability de um cargo interno")
    @DeleteMapping("/team/roles/{roleId}/capabilities/{capability}")
    public RoleCapabilityGrantsResponse revokeRole(
        Authentication authentication,
        @PathVariable Integer communityId,
        @PathVariable Integer roleId,
        @PathVariable String capability
    ) {
        return service.revokeRole(authentication, communityId, roleId, capability);
    }
}
