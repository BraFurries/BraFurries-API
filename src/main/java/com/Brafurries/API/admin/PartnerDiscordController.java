package com.Brafurries.API.admin;

import com.Brafurries.API.admin.PartnerDiscordService.ConfigRequest;
import com.Brafurries.API.admin.PartnerDiscordService.AnnouncementRequest;
import com.Brafurries.API.admin.PartnerDiscordService.PartnerOptionRequest;
import com.Brafurries.API.admin.PartnerDiscordService.PartnerView;
import com.Brafurries.API.admin.PartnerDiscordService.PostRequest;
import com.Brafurries.API.admin.PartnerDiscordService.Settings;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The site never obtains the Coddy internal token or requests Discord effects
 * without the BraFurries platform authorization boundary.
 */
@RestController
public class PartnerDiscordController {
    private final PartnerDiscordService service;

    public PartnerDiscordController(PartnerDiscordService service) {
        this.service = service;
    }

    @GetMapping("/admin/partners/discord")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public Settings getConfig() {
        return service.settings();
    }

    @PutMapping("/admin/partners/discord")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public Settings updateConfig(Authentication auth, @RequestBody ConfigRequest request) {
        return service.updateConfig(auth, request);
    }

    @PutMapping("/admin/partners/{partnerId}/discord")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public PartnerView updatePartner(Authentication auth, @PathVariable int partnerId,
                                     @RequestBody PartnerOptionRequest request) {
        return service.updatePartnerOption(auth, partnerId, request);
    }

    @PutMapping("/admin/partners/{partnerId}/discord/announcement")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public PartnerView updateAnnouncement(Authentication auth, @PathVariable int partnerId,
                                          @RequestBody AnnouncementRequest request) {
        return service.updateAnnouncement(auth, partnerId, request);
    }

    @PostMapping("/admin/partners/{partnerId}/discord/sync")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public PartnerView sync(Authentication auth, @PathVariable int partnerId) {
        return service.sync(auth, partnerId);
    }

    @DeleteMapping("/admin/partners/{partnerId}/discord")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public void unpublish(Authentication auth, @PathVariable int partnerId) {
        service.unpublish(auth, partnerId);
    }

    @PostMapping("/admin/partners/discord/sync")
    @PreAuthorize("@partnerAuthorizationService.canManage(authentication)")
    public List<PartnerView> syncAll(Authentication auth) {
        return service.syncAll(auth);
    }

    @GetMapping("/user/me/partners/discord")
    @PreAuthorize("isAuthenticated()")
    public List<PartnerDiscordService.OwnPublication> myPublications(Authentication auth) {
        return service.myPublications(auth);
    }

    @PostMapping("/user/me/partners/{partnerId}/discord/posts")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> post(Authentication auth, @PathVariable int partnerId,
                                    @Valid @RequestBody PostRequest request) {
        return service.publish(auth, partnerId, request);
    }
}
