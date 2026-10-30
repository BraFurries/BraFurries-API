package com.Brafurries.API.user;

import com.Brafurries.API.user.TemporaryRoleGrantService.*;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/temporary-roles")
public class TemporaryRoleGrantController {
    private final TemporaryRoleGrantService service;
    public TemporaryRoleGrantController(TemporaryRoleGrantService service) {
        this.service=service;
    }
    @GetMapping
    public List<Grant> list(Authentication auth,@PathVariable String guildId) {
        return service.list(auth,guildId);
    }
    @PostMapping
    public Grant create(Authentication auth,@PathVariable String guildId,
                        @RequestBody Create request) {
        return service.create(auth,guildId,request);
    }
    @PatchMapping("/{grantId}")
    public Grant renew(Authentication auth,@PathVariable String guildId,
                       @PathVariable long grantId,@RequestBody Renew request) {
        return service.renew(auth,guildId,grantId,request);
    }
    @DeleteMapping("/{grantId}")
    public Grant cancel(Authentication auth,@PathVariable String guildId,
                        @PathVariable long grantId,@RequestParam long expectedVersion) {
        return service.cancel(auth,guildId,grantId,expectedVersion);
    }
}
