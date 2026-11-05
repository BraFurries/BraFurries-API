package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.social.DiscordSelfServiceLinkService.Confirmation;
import com.Brafurries.API.auth.social.DiscordSelfServiceLinkService.Preview;
import com.Brafurries.API.auth.social.DiscordSelfServiceLinkService.Start;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Requires the current BraFurries JWT on every self-service action. */
@RestController
@RequestMapping("/user/me/discord-link")
public class DiscordSelfServiceLinkController {
    private final DiscordSelfServiceLinkService links;

    public DiscordSelfServiceLinkController(DiscordSelfServiceLinkService links) {
        this.links = links;
    }

    @PostMapping("/start")
    public Start start(Authentication auth) {
        return links.start(auth);
    }

    @GetMapping("/preview")
    public Preview preview(Authentication auth, @RequestParam String ticket) {
        return links.preview(auth, ticket);
    }

    @PostMapping("/confirm")
    public Confirmation confirm(Authentication auth, @RequestBody Map<String, String> request) {
        return links.confirm(auth, request.get("ticket"));
    }
}
