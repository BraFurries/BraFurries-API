package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMember;
import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMembersResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/members")
public class CommunityMemberController {
    private final CommunityMemberService service;

    public CommunityMemberController(CommunityMemberService service) {
        this.service = service;
    }

    @Operation(summary = "Lista os membros da comunidade vinculada ao servidor gerenciado")
    @GetMapping
    public CommunityMembersResponse list(
        Authentication auth,
        @PathVariable String guildId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String search
    ) {
        return service.list(auth, guildId, page, pageSize, search);
    }

    @Operation(summary = "Consulta um membro da comunidade vinculada ao servidor gerenciado")
    @GetMapping("/{userId}")
    public CommunityMember get(
        Authentication auth,
        @PathVariable String guildId,
        @PathVariable Integer userId
    ) {
        return service.get(auth, guildId, userId);
    }
}
