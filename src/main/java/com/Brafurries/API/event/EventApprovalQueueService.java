package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

@Service
public class EventApprovalQueueService {

    private final EventRepository eventRepository;
    private final EventSchedulingRepository eventSchedulingRepository;
    private final UserRepository userRepository;
    private final UserWarningRepository userWarningRepository;

    public EventApprovalQueueService(
            EventRepository eventRepository,
            EventSchedulingRepository eventSchedulingRepository,
            UserRepository userRepository,
            UserWarningRepository userWarningRepository
    ) {
        this.eventRepository = eventRepository;
        this.eventSchedulingRepository = eventSchedulingRepository;
        this.userRepository = userRepository;
        this.userWarningRepository = userWarningRepository;
    }

    public List<PendingApprovalEventView> listPendingApprovalEvents(Authentication authentication) {
        return listPendingApprovalEvents(authentication, null);
    }

    public List<PendingApprovalEventView> listPendingApprovalEvents(Authentication authentication, Integer relatedUserId) {
        List<Event> pendingEvents = resolvePendingEventsForAuthentication(authentication, relatedUserId);
        if (pendingEvents.isEmpty()) {
            return List.of();
        }

        List<Event> allEvents = eventRepository.findAllWithLocaleAndHostUser();
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = resolveLatestSchedulesByEventId(allEvents);
        Map<Integer, Long> warningCountByUserId = resolveWarningCountByUserId(pendingEvents);
        HighlightContext highlightContext = buildHighlightContext(allEvents, latestSchedulesByEventId);

        LocalDateTime now = LocalDateTime.now();
        return pendingEvents.stream()
                .map(event -> toPendingView(
                        event,
                        latestSchedulesByEventId.get(event.getId()),
                        warningCountByUserId,
                        highlightContext.eventCountByLocaleId(),
                        highlightContext.eventCountByUserId(),
                        highlightContext.eventCountByMonth(),
                        now
                ))
                .sorted(Comparator
                        .comparingLong(PendingApprovalEventView::warningCount)
                        .thenComparingLong(PendingApprovalEventView::localeEventCount)
                        .thenComparing(Comparator.comparingInt(PendingApprovalEventView::informationScore).reversed())
                        .thenComparingLong(PendingApprovalEventView::urgencyMinutes)
                        .thenComparing(view -> view.event().getId()))
                .toList();
    }

    public Page<PendingApprovalEventView> listPendingApprovalEvents(Authentication authentication, Integer relatedUserId, Pageable pageable) {
        List<PendingApprovalEventView> sortedEvents = listPendingApprovalEvents(authentication, relatedUserId);
        int safePage = pageable == null ? 0 : Math.max(pageable.getPageNumber(), 0);
        int safeSize = pageable == null ? 10 : Math.min(Math.max(pageable.getPageSize(), 1), 50);
        Pageable safePageable = PageRequest.of(safePage, safeSize);
        int fromIndex = (int) Math.min(safePageable.getOffset(), sortedEvents.size());
        int toIndex = Math.min(fromIndex + safePageable.getPageSize(), sortedEvents.size());

        return new PageImpl<>(sortedEvents.subList(fromIndex, toIndex), safePageable, sortedEvents.size());
    }

    private List<Event> resolvePendingEventsForAuthentication(Authentication authentication, Integer relatedUserId) {
        if (relatedUserId != null) {
            return eventRepository.findPendingApprovalEventsByRelatedUserId(relatedUserId);
        }

        if (canSeeAllPendingEvents(authentication)) {
            return eventRepository.findPendingApprovalEvents();
        }

        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return List.of();
        }

        return userRepository.findByEmail(authentication.getName())
                .map(user -> eventRepository.findPendingApprovalEventsByRelatedUserId(user.getId()))
                .orElse(List.of());
    }

    private boolean canSeeAllPendingEvents(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority)
                        || "admin:full".equals(authority)
                        || "events:manage".equals(authority));
    }

    public Map<Integer, List<String>> resolveHighlights(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Map.of();
        }

        List<Event> allEvents = eventRepository.findAllWithLocaleAndHostUser();
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = resolveLatestSchedulesByEventId(allEvents);
        HighlightContext highlightContext = buildHighlightContext(allEvents, latestSchedulesByEventId);
        Map<Integer, List<String>> highlightsByEventId = new LinkedHashMap<>();
        for (Event event : events) {
            if (event == null || event.getId() == null) {
                continue;
            }
            highlightsByEventId.put(event.getId(), buildHighlights(event, latestSchedulesByEventId.get(event.getId()), highlightContext));
        }
        return highlightsByEventId;
    }

    private PendingApprovalEventView toPendingView(
            Event event,
            EventSchedulingRepository.LatestEventScheduleProjection latestSchedule,
            Map<Integer, Long> warningCountByUserId,
            Map<Integer, Long> eventCountByLocaleId,
            Map<Integer, Long> eventCountByUserId,
            Map<YearMonth, Long> eventCountByMonth,
            LocalDateTime now
    ) {
        Integer hostUserId = event.getHostUser() != null ? event.getHostUser().getId() : null;
        Integer localeId = event.getLocale() != null ? event.getLocale().getId() : null;
        long warningCount = hostUserId == null ? Long.MAX_VALUE : warningCountByUserId.getOrDefault(hostUserId, 0L);
        long localeEventCount = localeId == null ? Long.MAX_VALUE : eventCountByLocaleId.getOrDefault(localeId, 0L);
        List<String> highlights = buildHighlights(event, latestSchedule, new HighlightContext(eventCountByLocaleId, eventCountByUserId, eventCountByMonth));

        return new PendingApprovalEventView(
                event,
                highlights,
                warningCount,
                localeEventCount,
                calculateInformationScore(event),
                calculateUrgencyMinutes(event, latestSchedule, now)
        );
    }

    private Map<Integer, Long> resolveWarningCountByUserId(List<Event> pendingEvents) {
        List<Integer> userIds = pendingEvents.stream()
                .map(Event::getHostUser)
                .filter(user -> user != null && user.getId() != null)
                .map(user -> user.getId())
                .distinct()
                .toList();

        if (userIds.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Long> warningCountByUserId = new HashMap<>();
        for (UserWarningRepository.UserWarningCountProjection projection : userWarningRepository.countActiveWarningsByUserIds(userIds)) {
            warningCountByUserId.put(projection.getUserId(), projection.getWarningCount());
        }
        return warningCountByUserId;
    }

    private HighlightContext buildHighlightContext(List<Event> allEvents, Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId) {
        Map<Integer, Long> eventCountByLocaleId = new HashMap<>();
        Map<Integer, Long> eventCountByUserId = new HashMap<>();
        Map<YearMonth, Long> eventCountByMonth = new HashMap<>();

        for (Event event : allEvents) {
            if (event.getLocale() != null && event.getLocale().getId() != null) {
                eventCountByLocaleId.merge(event.getLocale().getId(), 1L, Long::sum);
            }

            if (event.getHostUser() != null && event.getHostUser().getId() != null) {
                eventCountByUserId.merge(event.getHostUser().getId(), 1L, Long::sum);
            }

            LocalDateTime startingDatetime = resolveStartingDatetime(event, latestSchedulesByEventId.get(event.getId()));
            if (startingDatetime != null) {
                eventCountByMonth.merge(YearMonth.from(startingDatetime), 1L, Long::sum);
            }
        }

        return new HighlightContext(eventCountByLocaleId, eventCountByUserId, eventCountByMonth);
    }

    private List<String> buildHighlights(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule, HighlightContext highlightContext) {
        Integer hostUserId = event.getHostUser() != null ? event.getHostUser().getId() : null;
        Integer localeId = event.getLocale() != null ? event.getLocale().getId() : null;
        LocalDateTime startingDatetime = resolveStartingDatetime(event, latestSchedule);
        YearMonth eventMonth = startingDatetime != null ? YearMonth.from(startingDatetime) : null;
        long hostEventCount = hostUserId == null ? 0L : highlightContext.eventCountByUserId().getOrDefault(hostUserId, 0L);

        List<String> highlights = new ArrayList<>();
        if (eventMonth != null && highlightContext.eventCountByMonth().getOrDefault(eventMonth, 0L) == 1L) {
            highlights.add("unico_no_mes");
        }
        if (localeId != null && highlightContext.eventCountByLocaleId().getOrDefault(localeId, 0L) == 1L) {
            highlights.add("unico_no_locale");
        }
        if (hostEventCount > 1) {
            highlights.add("usuario_com_mais_de_um_evento");
        }
        return highlights;
    }

    private int calculateInformationScore(Event event) {
        int score = 0;

        if (hasText(event.getDescription())) score += 1;
        if (event.getDescription() != null && event.getDescription().trim().length() >= 80) score += 1;
        if (hasText(event.getPointName())) score += 1;
        if (event.getPrice() != null && event.getPrice() > 0D) score += 1;
        if (event.getMaxPrice() != null) score += 1;
        if (Boolean.TRUE.equals(event.getPriceConfirmed())) score += 1;
        if (hasText(event.getGroupChatLink())) score += 1;
        if (hasText(event.getWebsite())) score += 1;
        if (hasText(event.getTicketUrl())) score += 1;
        if (hasText(event.getEventLogoUrl())) score += 1;

        return score;
    }

    private long calculateUrgencyMinutes(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule, LocalDateTime now) {
        LocalDateTime startingDatetime = resolveStartingDatetime(event, latestSchedule);
        if (startingDatetime == null) {
            return Long.MAX_VALUE;
        }
        return Duration.between(now, startingDatetime).toMinutes();
    }

    private Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> resolveLatestSchedulesByEventId(List<Event> events) {
        List<Integer> eventIds = events.stream()
                .map(Event::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (eventIds.isEmpty()) {
            return Map.of();
        }

        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = new HashMap<>();
        for (EventSchedulingRepository.LatestEventScheduleProjection projection : eventSchedulingRepository.findLatestSchedulesByEventIds(eventIds)) {
            latestSchedulesByEventId.put(projection.getEventId(), projection);
        }
        return latestSchedulesByEventId;
    }

    private LocalDateTime resolveStartingDatetime(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule) {
        if (latestSchedule != null && latestSchedule.getStartingDatetime() != null) {
            return latestSchedule.getStartingDatetime();
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record PendingApprovalEventView(
            Event event,
            List<String> highlights,
            long warningCount,
            long localeEventCount,
            int informationScore,
            long urgencyMinutes
    ) {
    }

    private record HighlightContext(
            Map<Integer, Long> eventCountByLocaleId,
            Map<Integer, Long> eventCountByUserId,
            Map<YearMonth, Long> eventCountByMonth
    ) {
    }
}
