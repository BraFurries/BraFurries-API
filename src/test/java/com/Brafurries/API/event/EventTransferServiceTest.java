package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.event.EventTransferLog;
import com.Brafurries.API.entity.event.EventTransferRequest;
import com.Brafurries.API.entity.invite.Invite;
import com.Brafurries.API.entity.invite.InviteStatus;
import com.Brafurries.API.entity.invite.InviteType;
import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.event.EventTransferLogRepository;
import com.Brafurries.API.repository.event.EventTransferRequestRepository;
import com.Brafurries.API.repository.invite.InviteRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventTransferServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventStaffRepository eventStaffRepository;

    @Mock
    private EventTransferRequestRepository eventTransferRequestRepository;

    @Mock
    private EventTransferLogRepository eventTransferLogRepository;

    @Mock
    private InviteRepository inviteRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EventTransferService eventTransferService;

    @Test
    void requestTransferCreatesPendingRequestLogAndEmail() {
        User owner = user(1, "owner@example.com", "Owner");
        User target = user(2, "target@example.com", "Target");
        Event event = event(owner);
        Authentication authentication = authentication(owner);

        when(eventRepository.findWithLocaleById(10)).thenReturn(Optional.of(event));
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userRepository.findByEmail(target.getEmail())).thenReturn(Optional.of(target));
        when(eventTransferRequestRepository.existsByEventIdAndInviteStatus(10, InviteStatus.PENDING)).thenReturn(false);
        when(inviteRepository.save(any(Invite.class))).thenAnswer(invocation -> {
            Invite invite = invocation.getArgument(0);
            invite.setId(50L);
            return invite;
        });
        when(eventTransferRequestRepository.save(any(EventTransferRequest.class))).thenAnswer(invocation -> {
            EventTransferRequest request = invocation.getArgument(0);
            request.setId(100L);
            return request;
        });

        EventDtos.EventTransferRequestDto response = eventTransferService.requestTransfer(
                10,
                authentication,
                new EventDtos.CreateEventTransferRequestDto(null, target.getEmail(), "Organizador principal mudou")
        );

        assertEquals(100L, response.id());
        assertEquals(10, response.eventId());
        assertEquals(target.getId(), response.targetUserId());
        assertEquals("Organizador principal mudou", response.reason());
        verify(eventTransferLogRepository).save(any(EventTransferLog.class));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void acceptTransferChangesOwnerPromotesTargetAndDeletesRequest() {
        User owner = user(1, "owner@example.com", "Owner");
        User target = user(2, "target@example.com", "Target");
        Event event = event(owner);
        Invite invite = invite(owner, target);
        EventTransferRequest request = transferRequest(invite, event, owner);
        EventStaff ownerStaff = staff(event, owner, "Dono");
        Authentication authentication = authentication(target);

        when(inviteRepository.findByToken("token")).thenReturn(Optional.of(invite));
        when(eventTransferRequestRepository.findByInviteId(invite.getId())).thenReturn(Optional.of(request));
        when(userRepository.findByEmail(target.getEmail())).thenReturn(Optional.of(target));
        when(eventRepository.save(event)).thenReturn(event);
        when(eventRepository.findWithLocaleById(10)).thenReturn(Optional.of(event));
        when(eventStaffRepository.findByEventIdAndUserId(10, target.getId())).thenReturn(Optional.empty());
        when(eventStaffRepository.findByEventIdAndUserId(10, owner.getId())).thenReturn(Optional.of(ownerStaff));

        Event updated = eventTransferService.acceptTransfer("token", authentication);

        assertEquals(target.getId(), updated.getHostUser().getId());
        ArgumentCaptor<EventStaff> staffCaptor = ArgumentCaptor.forClass(EventStaff.class);
        verify(eventStaffRepository).save(staffCaptor.capture());
        assertEquals(target.getId(), staffCaptor.getValue().getUser().getId());
        assertEquals("Dono", staffCaptor.getValue().getCargo());
        assertEquals(InviteStatus.ACCEPTED, invite.getStatus());
        verify(eventStaffRepository).delete(ownerStaff);
        verify(eventTransferLogRepository).save(any(EventTransferLog.class));
        verify(inviteRepository).save(invite);
    }

    @Test
    void validateTransferTokenReturnsRequestData() {
        User owner = user(1, "owner@example.com", "Owner");
        User target = user(2, "target@example.com", "Target");
        Event event = event(owner);
        Invite invite = invite(owner, target);
        EventTransferRequest request = transferRequest(invite, event, owner);

        when(eventTransferRequestRepository.findByInviteId(invite.getId())).thenReturn(Optional.of(request));

        EventDtos.InviteDto response = eventTransferService.toInviteDto(invite);

        assertEquals(true, response.valid());
        assertEquals("EVENT_TRANSFER", response.type());
        assertEquals("PENDING", response.status());
        assertEquals("Organizador principal mudou", response.reason());
        assertEquals(10, response.inviteData().eventId());
        assertEquals(owner.getId(), response.inviteData().originalOwnerUserId());
    }

    private Authentication authentication(User user) {
        return new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of());
    }

    private Invite invite(User owner, User target) {
        Invite invite = new Invite();
        invite.setId(50L);
        invite.setToken("token");
        invite.setType(InviteType.EVENT_TRANSFER);
        invite.setStatus(InviteStatus.PENDING);
        invite.setRequestedByUser(owner);
        invite.setTargetUser(target);
        invite.setReason("Organizador principal mudou");
        invite.setCreatedAt(LocalDateTime.now());
        return invite;
    }

    private EventTransferRequest transferRequest(Invite invite, Event event, User owner) {
        EventTransferRequest request = new EventTransferRequest();
        request.setId(100L);
        request.setInvite(invite);
        request.setEvent(event);
        request.setOriginalOwnerUser(owner);
        return request;
    }

    private Event event(User owner) {
        Locale locale = new Locale();
        locale.setId(1);
        locale.setLocaleAbbrev("SP");
        locale.setLocaleName("Sao Paulo");

        Event event = new Event();
        event.setId(10);
        event.setHostUser(owner);
        event.setLocale(locale);
        event.setEventName("Evento");
        event.setCity("Sao Paulo");
        event.setAddress("Rua Teste");
        event.setPrice(0D);
        event.setPriceConfirmed(false);
        event.setOutOfTickets(false);
        event.setSalesEnded(false);
        event.setApproved(true);
        event.setIsEvent(true);
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    private EventStaff staff(Event event, User user, String cargo) {
        EventStaff staff = new EventStaff();
        staff.setEvent(event);
        staff.setUser(user);
        staff.setMngAgenda(true);
        staff.setEditEvent(true);
        staff.setMngStaff(true);
        staff.setCargo(cargo);
        return staff;
    }

    private User user(Integer id, String email, String displayName) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setUsername(displayName.toLowerCase());
        assertNotNull(user);
        return user;
    }
}
