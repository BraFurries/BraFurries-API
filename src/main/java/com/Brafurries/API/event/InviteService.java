package com.Brafurries.API.event;

import com.Brafurries.API.common.dto.ApiMessageResponse;
import com.Brafurries.API.entity.invite.Invite;
import com.Brafurries.API.entity.invite.InviteType;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.invite.InviteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InviteService {

    private final InviteRepository inviteRepository;
    private final EventTransferService eventTransferService;

    public InviteService(InviteRepository inviteRepository, EventTransferService eventTransferService) {
        this.inviteRepository = inviteRepository;
        this.eventTransferService = eventTransferService;
    }

    @Transactional(readOnly = true)
    public EventDtos.InviteDto validateEventTransferInvite(String token) {
        Invite invite = findInvite(token);
        validateType(invite, InviteType.EVENT_TRANSFER);
        return eventTransferService.toInviteDto(invite);
    }

    @Transactional
    public ApiMessageResponse acceptInvite(String token, Authentication authentication) {
        Invite invite = findInvite(token);
        if (invite.getType() == InviteType.EVENT_TRANSFER) {
            eventTransferService.acceptTransferInvite(invite, authentication);
            return new ApiMessageResponse(true, "Convite aceito");
        }
        throw unsupportedInviteType(invite);
    }

    @Transactional
    public ApiMessageResponse rejectInvite(String token, Authentication authentication) {
        Invite invite = findInvite(token);
        if (invite.getType() == InviteType.EVENT_TRANSFER) {
            eventTransferService.rejectTransferInvite(invite, authentication);
            return new ApiMessageResponse(true, "Convite recusado");
        }
        throw unsupportedInviteType(invite);
    }

    private Invite findInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token é obrigatório");
        }
        return inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
    }

    private void validateType(Invite invite, InviteType expectedType) {
        if (invite.getType() != expectedType) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado");
        }
    }

    private ResponseStatusException unsupportedInviteType(Invite invite) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de convite não suportado: " + invite.getType());
    }
}
