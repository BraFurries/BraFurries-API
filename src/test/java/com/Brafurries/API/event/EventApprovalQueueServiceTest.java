package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventApprovalQueueServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventSchedulingRepository eventSchedulingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserWarningRepository userWarningRepository;

    @InjectMocks
    private EventApprovalQueueService eventApprovalQueueService;

    @Test
    void listPendingApprovalEventsOrdersByConfiguredPriorityAndAddsHighlights() {
        Locale saoPaulo = locale(1, "SP", "Sao Paulo");
        Locale rio = locale(2, "RJ", "Rio de Janeiro");
        Locale minas = locale(3, "MG", "Minas Gerais");

        User userA = user(10, "a@example.com", "User A");
        User userB = user(20, "b@example.com", "User B");
        User userC = user(30, "c@example.com", "User C");

        Event approvedA = event(1, userA, saoPaulo, LocalDateTime.of(2026, 8, 10, 12, 0), true, null, null, null, null);
        Event pendingA = event(2, userA, saoPaulo, LocalDateTime.of(2026, 6, 10, 12, 0), null, "Descricao longa com bastante detalhe para pontuar melhor no score de informacoes do evento.", "Ponto X", "https://site", null);
        Event pendingB = event(3, userB, rio, LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null, null, null);
        Event pendingC = event(4, userC, minas, LocalDateTime.of(2026, 12, 15, 12, 0), null, null, null, null, null);
        approvedA.setCreatedAt(LocalDateTime.of(2026, 5, 20, 12, 0));
        pendingA.setCreatedAt(LocalDateTime.of(2026, 5, 10, 12, 0));
        pendingB.setCreatedAt(LocalDateTime.of(2026, 5, 12, 12, 0));
        pendingC.setCreatedAt(LocalDateTime.of(2026, 5, 15, 12, 0));

        when(eventRepository.findPendingApprovalEvents()).thenReturn(List.of(pendingA, pendingB, pendingC));
        when(eventRepository.findAllWithLocaleAndHostUser()).thenReturn(List.of(approvedA, pendingA, pendingB, pendingC));
        when(eventSchedulingRepository.findLatestSchedulesByEventIds(anyList())).thenReturn(List.of(
                projection(1, LocalDateTime.of(2026, 8, 10, 12, 0), LocalDateTime.of(2026, 8, 10, 16, 0), null),
                projection(2, LocalDateTime.of(2026, 6, 10, 12, 0), LocalDateTime.of(2026, 6, 10, 16, 0), null),
                projection(3, LocalDateTime.of(2026, 6, 5, 12, 0), LocalDateTime.of(2026, 6, 5, 16, 0), null),
                projection(4, LocalDateTime.of(2026, 12, 15, 12, 0), LocalDateTime.of(2026, 12, 15, 16, 0), null)
        ));
        when(userWarningRepository.countActiveWarningsByUserIds(List.of(10, 20, 30))).thenReturn(List.of(
                projection(10, 2L),
                projection(20, 0L),
                projection(30, 5L)
        ));

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "token",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        List<EventApprovalQueueService.PendingApprovalEventView> result = eventApprovalQueueService.listPendingApprovalEvents(authentication);
        Map<Integer, List<String>> highlightsByEventId = result.stream()
                .collect(java.util.stream.Collectors.toMap(view -> view.event().getId(), EventApprovalQueueService.PendingApprovalEventView::highlights));

        assertEquals(List.of(3, 2, 4), result.stream().map(view -> view.event().getId()).toList());
        assertTrue(highlightsByEventId.get(3).contains("unico_no_locale"));
        assertFalse(highlightsByEventId.get(3).contains("unico_no_mes"));
        assertTrue(highlightsByEventId.get(2).contains("usuario_com_mais_de_um_evento"));
        assertFalse(highlightsByEventId.get(2).contains("unico_no_mes"));
        assertFalse(highlightsByEventId.get(2).contains("unico_no_locale"));
        assertTrue(highlightsByEventId.get(4).contains("unico_no_mes"));
        assertTrue(highlightsByEventId.get(4).contains("unico_no_locale"));
    }

    @Test
    void listPendingApprovalEventsLimitsRegularUsersToOwnedEvents() {
        Locale saoPaulo = locale(1, "SP", "Sao Paulo");
        User owner = user(10, "owner@example.com", "Owner");
        Event pendingOwned = event(1, owner, saoPaulo, LocalDateTime.of(2026, 6, 10, 12, 0), null, null, null, null, null);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                "token",
                List.of()
        );

        when(userRepository.findByEmail("owner@example.com")).thenReturn(java.util.Optional.of(owner));
        when(eventRepository.findPendingApprovalEventsByRelatedUserId(10)).thenReturn(List.of(pendingOwned));
        when(eventRepository.findAllWithLocaleAndHostUser()).thenReturn(List.of(pendingOwned));
        when(eventSchedulingRepository.findLatestSchedulesByEventIds(anyList())).thenReturn(List.of(
                projection(1, LocalDateTime.of(2026, 6, 10, 12, 0), LocalDateTime.of(2026, 6, 10, 16, 0), null)
        ));
        when(userWarningRepository.countActiveWarningsByUserIds(List.of(10))).thenReturn(List.of(projection(10, 0L)));

        List<EventApprovalQueueService.PendingApprovalEventView> result = eventApprovalQueueService.listPendingApprovalEvents(authentication);

        assertEquals(List.of(1), result.stream().map(view -> view.event().getId()).toList());
        verify(eventRepository).findPendingApprovalEventsByRelatedUserId(10);
    }

    @Test
    void listPendingApprovalEventsReturnsRequestedPageAfterPriorityOrdering() {
        Locale saoPaulo = locale(1, "SP", "Sao Paulo");
        Locale rio = locale(2, "RJ", "Rio de Janeiro");
        Locale minas = locale(3, "MG", "Minas Gerais");

        User userA = user(10, "a@example.com", "User A");
        User userB = user(20, "b@example.com", "User B");
        User userC = user(30, "c@example.com", "User C");

        Event pendingA = event(1, userA, saoPaulo, LocalDateTime.of(2026, 6, 10, 12, 0), null, null, null, null, null);
        Event pendingB = event(2, userB, rio, LocalDateTime.of(2026, 6, 5, 12, 0), null, null, null, null, null);
        Event pendingC = event(3, userC, minas, LocalDateTime.of(2026, 12, 15, 12, 0), null, null, null, null, null);

        when(eventRepository.findPendingApprovalEvents()).thenReturn(List.of(pendingA, pendingB, pendingC));
        when(eventRepository.findAllWithLocaleAndHostUser()).thenReturn(List.of(pendingA, pendingB, pendingC));
        when(eventSchedulingRepository.findLatestSchedulesByEventIds(anyList())).thenReturn(List.of(
                projection(1, LocalDateTime.of(2026, 6, 10, 12, 0), LocalDateTime.of(2026, 6, 10, 16, 0), null),
                projection(2, LocalDateTime.of(2026, 6, 5, 12, 0), LocalDateTime.of(2026, 6, 5, 16, 0), null),
                projection(3, LocalDateTime.of(2026, 12, 15, 12, 0), LocalDateTime.of(2026, 12, 15, 16, 0), null)
        ));
        when(userWarningRepository.countActiveWarningsByUserIds(List.of(10, 20, 30))).thenReturn(List.of(
                projection(10, 2L),
                projection(20, 0L),
                projection(30, 5L)
        ));

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "token",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        Page<EventApprovalQueueService.PendingApprovalEventView> result = eventApprovalQueueService.listPendingApprovalEvents(
                authentication,
                null,
                PageRequest.of(1, 2)
        );

        assertEquals(List.of(3), result.getContent().stream().map(view -> view.event().getId()).toList());
        assertEquals(3, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        assertFalse(result.hasNext());
        assertTrue(result.hasPrevious());
    }

    @Test
    void listPendingApprovalEventsFiltersByProvidedRelatedUserId() {
        Locale saoPaulo = locale(1, "SP", "Sao Paulo");
        User owner = user(10, "owner@example.com", "Owner");
        Event pendingRelated = event(1, owner, saoPaulo, LocalDateTime.of(2026, 6, 10, 12, 0), null, null, null, null, null);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "token",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        when(eventRepository.findPendingApprovalEventsByRelatedUserId(20)).thenReturn(List.of(pendingRelated));
        when(eventRepository.findAllWithLocaleAndHostUser()).thenReturn(List.of(pendingRelated));
        when(eventSchedulingRepository.findLatestSchedulesByEventIds(anyList())).thenReturn(List.of(
                projection(1, LocalDateTime.of(2026, 6, 10, 12, 0), LocalDateTime.of(2026, 6, 10, 16, 0), null)
        ));
        when(userWarningRepository.countActiveWarningsByUserIds(List.of(10))).thenReturn(List.of(projection(10, 0L)));

        List<EventApprovalQueueService.PendingApprovalEventView> result = eventApprovalQueueService.listPendingApprovalEvents(authentication, 20);

        assertEquals(List.of(1), result.stream().map(view -> view.event().getId()).toList());
        verify(eventRepository).findPendingApprovalEventsByRelatedUserId(20);
    }

    private UserWarningRepository.UserWarningCountProjection projection(Integer userId, Long warningCount) {
        return new UserWarningRepository.UserWarningCountProjection() {
            @Override
            public Integer getUserId() {
                return userId;
            }

            @Override
            public Long getWarningCount() {
                return warningCount;
            }
        };
    }

    private EventSchedulingRepository.LatestEventScheduleProjection projection(
            Integer eventId,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            String gcalEventId
    ) {
        return new EventSchedulingRepository.LatestEventScheduleProjection() {
            @Override
            public Integer getEventId() {
                return eventId;
            }

            @Override
            public LocalDateTime getStartingDatetime() {
                return startingDatetime;
            }

            @Override
            public LocalDateTime getEndingDatetime() {
                return endingDatetime;
            }

            @Override
            public String getGcalEventId() {
                return gcalEventId;
            }
        };
    }

    private Event event(
            Integer id,
            User hostUser,
            Locale locale,
            LocalDateTime startingDatetime,
            Boolean approved,
            String description,
            String pointName,
            String website,
            String ticketUrl
    ) {
        Event event = new Event();
        event.setId(id);
        event.setHostUser(hostUser);
        event.setLocale(locale);
        event.setEventName("Event " + id);
        event.setCity("Cidade");
        event.setAddress("Endereco");
        event.setCreatedAt(startingDatetime.minusDays(1));
        event.setApproved(approved);
        event.setIsEvent(true);
        event.setPrice(0D);
        event.setPriceConfirmed(false);
        event.setOutOfTickets(false);
        event.setSalesEnded(false);
        event.setDescription(description);
        event.setPointName(pointName);
        event.setWebsite(website);
        event.setTicketUrl(ticketUrl);
        return event;
    }

    private User user(Integer id, String email, String displayName) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setUsername(displayName);
        return user;
    }

    private Locale locale(Integer id, String abbrev, String name) {
        Locale locale = new Locale();
        locale.setId(id);
        locale.setLocaleAbbrev(abbrev);
        locale.setLocaleName(name);
        return locale;
    }
}
