package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMemberDetail;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}/members")
public class CommunityMemberCommunityController {
    private final CommunityMemberService service;

    public CommunityMemberCommunityController(CommunityMemberService service) {
        this.service = service;
    }

    @Operation(summary = "Consulta detalhes administrativos de um membro dentro da Community")
    @GetMapping("/{userId}")
    public CommunityMemberDetail get(
        Authentication auth,
        @PathVariable Integer communityId,
        @PathVariable Integer userId
    ) {
        return service.getCommunityDetail(auth, communityId, userId);
    }
}
