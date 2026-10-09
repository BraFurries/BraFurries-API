package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.event.EventTransferLog;
import com.Brafurries.API.entity.event.EventTransferLogAction;
import com.Brafurries.API.entity.event.EventTransferRequest;
import com.Brafurries.API.entity.invite.Invite;
import com.Brafurries.API.entity.invite.InviteStatus;
import com.Brafurries.API.entity.invite.InviteType;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.event.EventTransferLogRepository;
import com.Brafurries.API.repository.event.EventTransferRequestRepository;
import com.Brafurries.API.repository.invite.InviteRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventTransferService {

    private static final Logger log = LoggerFactory.getLogger(EventTransferService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EventRepository eventRepository;
    private final EventStaffRepository eventStaffRepository;
    private final EventTransferRequestRepository eventTransferRequestRepository;
    private final EventTransferLogRepository eventTransferLogRepository;
    private final InviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final String frontendUrl;
    private final String mailFrom;

    public EventTransferService(
            EventRepository eventRepository,
            EventStaffRepository eventStaffRepository,
            EventTransferRequestRepository eventTransferRequestRepository,
            EventTransferLogRepository eventTransferLogRepository,
            InviteRepository inviteRepository,
            UserRepository userRepository,
            JavaMailSender mailSender,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${spring.mail.username}") String mailFrom
    ) {
        this.eventRepository = eventRepository;
        this.eventStaffRepository = eventStaffRepository;
        this.eventTransferRequestRepository = eventTransferRequestRepository;
        this.eventTransferLogRepository = eventTransferLogRepository;
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.frontendUrl = frontendUrl;
        this.mailFrom = mailFrom;
    }

    @Transactional
    public EventDtos.EventTransferRequestDto requestTransfer(Integer eventId, Authentication authentication, EventDtos.CreateEventTransferRequestDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisição é obrigatório");
        }
        String reason = normalizeReason(body.reason());

        Event event = findEvent(eventId);
        User actor = findAuthenticatedUser(authentication);
        User originalOwner = event.getHostUser();
        if (originalOwner == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento não possui dono atual");
        }
        if (!canRequestTransfer(authentication, event, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para transferir este evento");
        }
        if (eventTransferRequestRepository.existsByEventIdAndInviteStatus(eventId, InviteStatus.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma solicitação de transferência pendente para este evento");
        }

        User targetUser = resolveTargetUser(body);
        if (Objects.equals(originalOwner.getId(), targetUser.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O novo dono precisa ser diferente do dono atual");
        }
        if (targetUser.getEmail() == null || targetUser.getEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O usuário de destino precisa ter e-mail cadastrado");
        }

        Invite invite = new Invite();
        invite.setToken(generateToken());
        invite.setType(InviteType.EVENT_TRANSFER);
        invite.setStatus(InviteStatus.PENDING);
        invite.setRequestedByUser(actor);
        invite.setTargetUser(targetUser);
        invite.setReason(reason);
        invite.setCreatedAt(LocalDateTime.now());
        Invite savedInvite = inviteRepository.save(invite);

        EventTransferRequest request = new EventTransferRequest();
        request.setInvite(savedInvite);
        request.setEvent(event);
        request.setOriginalOwnerUser(originalOwner);
        EventTransferRequest savedRequest = eventTransferRequestRepository.save(request);
        saveLog(savedInvite, event, actor, originalOwner, targetUser, EventTransferLogAction.REQUESTED, reason);

        sendTransferInvitationEmail(savedRequest);
        log.info("Transferência de evento solicitada: eventId={}, inviteId={}, fromUserId={}, toUserId={}, actorUserId={}",
                event.getId(), savedInvite.getId(), originalOwner.getId(), targetUser.getId(), actor.getId());

        return toDto(savedRequest);
    }

    EventDtos.InviteDto toInviteDto(Invite invite) {
        EventTransferRequest request = findRequestByInvite(invite);
        return new EventDtos.InviteDto(
                true,
                invite.getType().name(),
                invite.getStatus().name(),
                invite.getId(),
                invite.getRequestedByUser().getId(),
                displayName(invite.getRequestedByUser()),
                invite.getTargetUser().getId(),
                displayName(invite.getTargetUser()),
                invite.getReason(),
                invite.getCreatedAt(),
                invite.getRespondedAt(),
                new EventDtos.EventTransferInviteDataDto(
                        request.getEvent().getId(),
                        request.getEvent().getEventName(),
                        request.getOriginalOwnerUser().getId(),
                        displayName(request.getOriginalOwnerUser())
                )
        );
    }

    @Transactional
    public Event acceptTransfer(String token, Authentication authentication) {
        return acceptTransferInvite(findInvite(token), authentication);
    }

    Event acceptTransferInvite(Invite invite, Authentication authentication) {
        EventTransferRequest request = findRequestByInvite(invite);
        User actor = findAuthenticatedUser(authentication);
        validatePendingInvite(invite);
        validateTargetActor(invite, actor);

        Event event = request.getEvent();
        User originalOwner = request.getOriginalOwnerUser();
        User targetUser = invite.getTargetUser();
        event.setHostUser(targetUser);
        eventRepository.save(event);
        promoteTargetToOwnerStaff(event, targetUser);
        removePreviousOwnerStaff(event, originalOwner, targetUser);

        invite.setStatus(InviteStatus.ACCEPTED);
        invite.setRespondedAt(LocalDateTime.now());
        inviteRepository.save(invite);
        saveLog(invite, event, actor, originalOwner, targetUser, EventTransferLogAction.ACCEPTED, invite.getReason());

        log.info("Transferência de evento aceita: eventId={}, inviteId={}, fromUserId={}, toUserId={}",
                event.getId(), invite.getId(), originalOwner.getId(), targetUser.getId());

        return eventRepository.findWithLocaleById(event.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
    }

    @Transactional
    public void rejectTransfer(String token, Authentication authentication) {
        rejectTransferInvite(findInvite(token), authentication);
    }

    void rejectTransferInvite(Invite invite, Authentication authentication) {
        EventTransferRequest request = findRequestByInvite(invite);
        User actor = findAuthenticatedUser(authentication);
        validatePendingInvite(invite);
        validateTargetActor(invite, actor);

        Event event = request.getEvent();
        User originalOwner = request.getOriginalOwnerUser();
        User targetUser = invite.getTargetUser();

        invite.setStatus(InviteStatus.REJECTED);
        invite.setRespondedAt(LocalDateTime.now());
        inviteRepository.save(invite);
        saveLog(invite, event, actor, originalOwner, targetUser, EventTransferLogAction.REJECTED, invite.getReason());
        sendTransferRejectedEmail(request);

        log.info("Transferência de evento recusada: eventId={}, inviteId={}, fromUserId={}, toUserId={}",
                event.getId(), invite.getId(), originalOwner.getId(), targetUser.getId());
    }

    private void promoteTargetToOwnerStaff(Event event, User targetUser) {
        eventStaffRepository.findByEventIdAndUserId(event.getId(), targetUser.getId()).ifPresentOrElse(staff -> {
            staff.setMngAgenda(true);
            staff.setEditEvent(true);
            staff.setMngStaff(true);
            staff.setCargo("Dono");
            eventStaffRepository.save(staff);
        }, () -> {
            EventStaff staff = new EventStaff();
            staff.setEvent(event);
            staff.setUser(targetUser);
            staff.setMngAgenda(true);
            staff.setEditEvent(true);
            staff.setMngStaff(true);
            staff.setCargo("Dono");
            eventStaffRepository.save(staff);
        });
    }

    private void removePreviousOwnerStaff(Event event, User originalOwner, User targetUser) {
        if (Objects.equals(originalOwner.getId(), targetUser.getId())) {
            return;
        }

        eventStaffRepository.findByEventIdAndUserId(event.getId(), originalOwner.getId())
                .ifPresent(eventStaffRepository::delete);
    }

    private Invite findInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token é obrigatório");
        }
        return inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite não encontrado"));
    }

    private EventTransferRequest findRequestByInvite(Invite invite) {
        return eventTransferRequestRepository.findByInviteId(invite.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dados de transferência do convite não encontrados"));
    }

    private Event findEvent(Integer eventId) {
        return eventRepository.findWithLocaleById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
    }

    private User findAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Não autenticado");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));
    }

    private User resolveTargetUser(EventDtos.CreateEventTransferRequestDto body) {
        if (body.targetUserId() != null) {
            return userRepository.findById(body.targetUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário de destino não encontrado"));
        }
        if (body.targetEmail() != null && !body.targetEmail().isBlank()) {
            return userRepository.findByEmail(body.targetEmail().trim().toLowerCase())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário de destino não encontrado"));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe targetUserId ou targetEmail");
    }

    private boolean canRequestTransfer(Authentication authentication, Event event, User actor) {
        boolean isOwner = event.getHostUser() != null && Objects.equals(event.getHostUser().getId(), actor.getId());
        return isOwner || hasAnyPermission(authentication, "ROLE_ADMIN", "admin:full", "events:manage");
    }

    private void validatePendingInvite(Invite invite) {
        if (invite.getStatus() != InviteStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Convite já foi respondido");
        }
    }

    private void validateTargetActor(Invite invite, User actor) {
        if (!Objects.equals(invite.getTargetUser().getId(), actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o destinatário pode responder esta solicitação");
        }
    }

    private void saveLog(Invite invite, Event event, User actor, User fromUser, User toUser, EventTransferLogAction action, String reason) {
        EventTransferLog transferLog = new EventTransferLog();
        transferLog.setInvite(invite);
        transferLog.setEvent(event);
        transferLog.setActorUser(actor);
        transferLog.setFromUser(fromUser);
        transferLog.setToUser(toUser);
        transferLog.setAction(action);
        transferLog.setReason(reason);
        transferLog.setCreatedAt(LocalDateTime.now());
        eventTransferLogRepository.save(transferLog);
    }

    private void sendTransferInvitationEmail(EventTransferRequest request) {
        Invite invite = request.getInvite();
        String eventName = request.getEvent().getEventName();
        String link = frontendUrl + "/convite/transferir-evento/" + invite.getToken();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(invite.getTargetUser().getEmail());
        message.setSubject("BraFurries - Convite para assumir evento");
        message.setText(
                "Olá!\n\n" +
                "Você recebeu um convite para assumir a organização do evento \"" + eventName + "\".\n\n" +
                "Motivo informado:\n" +
                invite.getReason() + "\n\n" +
                "Para aceitar ou recusar, acesse:\n" +
                link + "\n\n" +
                "Se você não esperava este convite, pode recusá-lo pelo link acima.\n\n" +
                "Atenciosamente,\n" +
                "Equipe BraFurries"
        );
        mailSender.send(message);
    }

    private void sendTransferRejectedEmail(EventTransferRequest request) {
        User originalOwner = request.getOriginalOwnerUser();
        if (originalOwner.getEmail() == null || originalOwner.getEmail().isBlank()) {
            return;
        }

        Invite invite = request.getInvite();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(originalOwner.getEmail());
        message.setSubject("BraFurries - Transferência de evento recusada");
        message.setText(
                "Olá!\n\n" +
                "O convite para transferir o evento \"" + request.getEvent().getEventName() + "\" para " +
                displayName(invite.getTargetUser()) + " foi recusado.\n\n" +
                "Motivo informado na solicitação:\n" +
                invite.getReason() + "\n\n" +
                "A propriedade do evento não foi alterada.\n\n" +
                "Atenciosamente,\n" +
                "Equipe BraFurries"
        );
        mailSender.send(message);
    }

    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) return user.getDisplayName();
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
        return user.getEmail();
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason é obrigatório");
        }
        String normalized = reason.trim();
        if (normalized.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason deve ter no máximo 512 caracteres");
        }
        return normalized;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean hasAnyPermission(Authentication authentication, String... permissions) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> {
                    for (String permission : permissions) {
                        if (permission.equals(authority)) return true;
                    }
                    return false;
                });
    }

    private EventDtos.EventTransferRequestDto toDto(EventTransferRequest request) {
        Invite invite = request.getInvite();
        return new EventDtos.EventTransferRequestDto(
                request.getId(),
                request.getEvent().getId(),
                request.getEvent().getEventName(),
                request.getOriginalOwnerUser().getId(),
                displayName(request.getOriginalOwnerUser()),
                invite.getTargetUser().getId(),
                displayName(invite.getTargetUser()),
                invite.getReason(),
                invite.getCreatedAt()
        );
    }
}
