package com.Brafurries.API.user;

import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventRepository.DashboardEventProjection;
import com.Brafurries.API.repository.user.UserLevelRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDashboardCachedQueryServiceTest {

    @Mock
    private UserLevelRepository userLevelRepository;

    @Mock
    private CommunityDiscordRepository communityDiscordRepository;

    @Mock
    private EventRepository eventRepository;

    @Test
    void findDashboardEventsByTypePrioritizesOnePartnerThenOneCommon() {
        DashboardEventProjection partner = mock(DashboardEventProjection.class);
        DashboardEventProjection common = mock(DashboardEventProjection.class);
        when(eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(eq(true), eq(true), any(LocalDateTime.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(partner));
        when(eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(eq(true), eq(false), any(LocalDateTime.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(common));

        UserDashboardCachedQueryService service = new UserDashboardCachedQueryService(
                userLevelRepository,
                communityDiscordRepository,
                eventRepository
        );

        List<DashboardEventProjection> result = service.findDashboardEventsByType(true, 123L);

        assertEquals(List.of(partner, common), result);
    }

    @Test
    void findDashboardEventsByTypeFallsBackToTwoClosestWhenNoPartnerExists() {
        DashboardEventProjection first = mock(DashboardEventProjection.class);
        DashboardEventProjection second = mock(DashboardEventProjection.class);
        when(eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(eq(false), eq(true), any(LocalDateTime.class), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of());
        when(eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(eq(false), eq(null), any(LocalDateTime.class), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(first, second));

        UserDashboardCachedQueryService service = new UserDashboardCachedQueryService(
                userLevelRepository,
                communityDiscordRepository,
                eventRepository
        );

        List<DashboardEventProjection> result = service.findDashboardEventsByType(false, 123L);

        assertEquals(List.of(first, second), result);
        verify(eventRepository).findUpcomingApprovedDashboardCardsByTypeAndPartner(eq(false), eq(null), any(LocalDateTime.class), eq(PageRequest.of(0, 2)));
    }
}
