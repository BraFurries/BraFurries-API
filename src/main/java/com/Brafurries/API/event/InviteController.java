package com.Brafurries.API.event;

import com.Brafurries.API.common.dto.ApiMessageResponse;
import com.Brafurries.API.event.dto.EventDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invites")
@Tag(name = "Convites", description = "Endpoints de convites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @Operation(summary = "Valida convite de transferência de evento")
    @GetMapping("/event-transfer/{token}")
    public EventDtos.InviteDto validateEventTransferInvite(@PathVariable String token) {
        return inviteService.validateEventTransferInvite(token);
    }

    @Operation(summary = "Aceita convite de transferência de evento")
    @PostMapping("/{token}/accept")
    public ApiMessageResponse acceptInvite(Authentication authentication, @PathVariable String token) {
        return inviteService.acceptInvite(token, authentication);
    }

    @Operation(summary = "Recusa convite de transferência de evento")
    @PostMapping("/{token}/reject")
    public ApiMessageResponse rejectInvite(Authentication authentication, @PathVariable String token) {
        return inviteService.rejectInvite(token, authentication);
    }
}
