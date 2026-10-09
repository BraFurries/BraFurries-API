package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityCoreDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities")
@Tag(name = "Communities", description = "Community ownership and onboarding for the authenticated user")
public class CommunityCoreController {
    private final CommunityCoreService service;

    public CommunityCoreController(CommunityCoreService service) {
        this.service = service;
    }

    @Operation(summary = "Lista Communities relacionadas ao usuário autenticado")
    @GetMapping
    public List<CommunityResponse> list(Authentication authentication) {
        return service.list(authentication);
    }

    @Operation(summary = "Consulta uma Community relacionada ao usuário autenticado")
    @GetMapping("/{communityId}")
    public CommunityResponse get(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.get(authentication, communityId);
    }

}
