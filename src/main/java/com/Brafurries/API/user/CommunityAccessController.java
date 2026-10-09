package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityAccessDtos.CommunityAccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}/access")
@Tag(name = "Community Access", description = "Effective Community capabilities for the authenticated user")
public class CommunityAccessController {
    private final CommunityAuthorizationService authorizationService;

    public CommunityAccessController(CommunityAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Operation(summary = "Retorna capabilities e escopos efetivos na Community")
    @GetMapping
    public CommunityAccessResponse getAccess(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return authorizationService.getAccess(authentication, communityId);
    }
}
