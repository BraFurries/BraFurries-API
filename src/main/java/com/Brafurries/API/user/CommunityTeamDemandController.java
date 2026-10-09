package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.CommunityTeamDemandDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/team/demands")
public class CommunityTeamDemandController {
    private final CommunityTeamDemandService service;
    public CommunityTeamDemandController(CommunityTeamDemandService service) { this.service = service; }

    @GetMapping public List<DemandResponse> list(Authentication auth, @PathVariable String guildId) { return service.list(auth, guildId); }
    @PostMapping public DemandResponse create(Authentication auth, @PathVariable String guildId, @RequestBody @Valid SaveDemandRequest request) { return service.create(auth, guildId, request); }
    @PutMapping("/{demandId}") public DemandResponse update(Authentication auth, @PathVariable String guildId, @PathVariable Integer demandId, @RequestBody @Valid SaveDemandRequest request) { return service.update(auth, guildId, demandId, request); }
    @DeleteMapping("/{demandId}") public void delete(Authentication auth, @PathVariable String guildId, @PathVariable Integer demandId) { service.delete(auth, guildId, demandId); }
}
