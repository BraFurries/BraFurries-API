package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.event.EventPartnershipService;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import com.Brafurries.API.repository.event.EventRepository.DashboardEventProjection;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.UserDashboardDtos.UserDashboardResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDiscordRepository userDiscordRepository;

    @Mock
    private UserDashboardCachedQueryService cachedQueryService;

    @Mock
    private EventPartnershipService eventPartnershipService;

    @Test
    void getLoggedUserDashboardPrioritizesOnePartnerThenOneCommon() {
        User user = new User();
        user.setId(10);
        user.setEmail("user@example.com");

        DashboardEventProjection nextManaged = event(1, "Plantao", "Portaria", "Rua 1", true, false);
        DashboardEventProjection partnerEvent = event(2, "Parceiro Evento", null, "Rua Parceira", true, true);
        DashboardEventProjection commonEvent = event(3, "Evento Comum", "Praca", "Rua Comum", true, false);
        DashboardEventProjection meetOne = event(4, "Meet Um", "Shopping", "Rua Meet 1", false, false);
        DashboardEventProjection meetTwo = event(5, "Meet Dois", null, "Rua Meet 2", false, false);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userDiscordRepository.findByUser(user)).thenReturn(Optional.empty());
        when(cachedQueryService.findUserLevel(10, 1)).thenReturn(null);
        when(cachedQueryService.findNextManagedEvent(eq("user@example.com"), any(Long.class))).thenReturn(nextManaged);
        when(cachedQueryService.findDashboardEventsByType(eq(true), any(Long.class))).thenReturn(List.of(partnerEvent, commonEvent));
        when(cachedQueryService.findDashboardEventsByType(eq(false), any(Long.class))).thenReturn(List.of(meetOne, meetTwo));
        when(eventPartnershipService.findActivePartnerEventIds(any())).thenReturn(Set.of(2));

        UserDashboardService service = new UserDashboardService(
                userRepository,
                userDiscordRepository,
                cachedQueryService,
                eventPartnershipService
        );

        UserDashboardResponse response = service.getLoggedUserDashboard(new UsernamePasswordAuthenticationToken("USER@example.com", null, List.of()));

        assertFalse(response.discord().connected());
        assertEquals(null, response.myStatus().level());
        assertEquals("", response.myStatus().eventsAtended());
        assertEquals("", response.myStatus().lastBadge());
        assertEquals(List.of(), response.servers());
        assertEquals(List.of(), response.analytics());
        assertEquals("Plantao", response.nextManagedEvent().eventName());
        assertEquals("Portaria", response.nextManagedEvent().point());
        assertEquals(List.of("Parceiro Evento", "Evento Comum"), response.events().stream().map(item -> item.eventName()).toList());
        assertEquals("Rua Parceira", response.events().get(0).point());
        assertEquals(List.of("Meet Um", "Meet Dois"), response.meets().stream().map(item -> item.eventName()).toList());
        assertEquals("Rua Meet 2", response.meets().get(1).point());
    }

    @Test
    void getLoggedUserDashboardReturnsAdminAnalyticsFromDiscordMetrics() {
        User user = new User();
        user.setId(10);
        user.setEmail("admin@example.com");
        GeneralDiscordMetricsProjection metrics = mock(GeneralDiscordMetricsProjection.class);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(userDiscordRepository.findByUser(user)).thenReturn(Optional.empty());
        when(cachedQueryService.findUserLevel(10, 1)).thenReturn(null);
        when(cachedQueryService.findNextManagedEvent(eq("admin@example.com"), any(Long.class))).thenReturn(null);
        when(cachedQueryService.findDashboardEventsByType(eq(true), any(Long.class))).thenReturn(List.of());
        when(cachedQueryService.findDashboardEventsByType(eq(false), any(Long.class))).thenReturn(List.of());
        when(eventPartnershipService.findActivePartnerEventIds(any())).thenReturn(Set.of());
        when(cachedQueryService.fetchGeneralDiscordMetrics()).thenReturn(metrics);
        when(metrics.getActiveDiscordCommunities()).thenReturn(12L);
        when(metrics.getTotalMembersReachedActiveCommunities()).thenReturn(3456L);

        UserDashboardService service = new UserDashboardService(
                userRepository,
                userDiscordRepository,
                cachedQueryService,
                eventPartnershipService
        );

        UserDashboardResponse response = service.getLoggedUserDashboard(new UsernamePasswordAuthenticationToken(
                "ADMIN@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));

        assertEquals("Servidores ativos", response.analytics().get(0).name());
        assertEquals("12", response.analytics().get(0).data());
        assertEquals("Total de furries alcançados", response.analytics().get(1).name());
        assertEquals("3456", response.analytics().get(1).data());
        assertEquals("Status do bot", response.analytics().get(2).name());
        assertEquals("Online", response.analytics().get(2).data());
    }

    private DashboardEventProjection event(Integer id, String name, String pointName, String address, Boolean isEvent, Boolean partnerEvent) {
        return new TestDashboardEventProjection(
                id,
                name,
                pointName,
                address,
                LocalDateTime.of(2026, 6, id, 10, 0),
                LocalDateTime.of(2026, 6, id, 12, 0),
                "https://cdn.example.com/events/" + id + ".webp",
                isEvent
        );
    }

    private record TestDashboardEventProjection(
            Integer id,
            String eventName,
            String pointName,
            String address,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            String eventLogoUrl,
            Boolean isEvent
    ) implements DashboardEventProjection {
        @Override
        public Integer getId() { return id; }

        @Override
        public String getEventName() { return eventName; }

        @Override
        public String getPointName() { return pointName; }

        @Override
        public String getAddress() { return address; }

        @Override
        public LocalDateTime getStartingDatetime() { return startingDatetime; }

        @Override
        public LocalDateTime getEndingDatetime() { return endingDatetime; }

        @Override
        public String getEventLogoUrl() { return eventLogoUrl; }

        @Override
        public Boolean getIsEvent() { return isEvent; }

    }
}
