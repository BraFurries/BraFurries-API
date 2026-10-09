package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/public")
@Tag(name = "Público", description = "Endpoints públicos")
public class PublicController {
    private final EventRepository eventRepository;
    private final EventSchedulingRepository eventSchedulingRepository;

    public PublicController(EventRepository eventRepository, EventSchedulingRepository eventSchedulingRepository) {
        this.eventRepository = eventRepository;
        this.eventSchedulingRepository = eventSchedulingRepository;
    }

    @Operation(summary = "Lista eventos públicos com filtros")
    @GetMapping("/events")
    public List<EventDtos.EventBasicDto> getPublicEvents(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "local") String location,
            @RequestParam(required = false) String name
    ) {
        YearMonth normalizedMonth = normalizeMonth(month);
        LocalDateTime monthStart = normalizedMonth == null ? null : normalizedMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = normalizedMonth == null ? null : normalizedMonth.plusMonths(1).atDay(1).atStartOfDay();
        List<Event> events = eventRepository.findAllWithFilters(monthStart, monthEnd, blankAsNull(state), blankAsNull(location), blankAsNull(name));
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedules = resolveLatestSchedules(events);
        return events
                .stream()
                .map(event -> toBasicDto(event, latestSchedules.get(event.getId())))
                .toList();
    }

    private EventDtos.EventBasicDto toBasicDto(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule) {
        return new EventDtos.EventBasicDto(
                event.getId(),
                event.getEventName(),
                event.getCity(),
                event.getLocale().getLocaleAbbrev(),
                latestSchedule != null ? latestSchedule.getStartingDatetime() : null,
                latestSchedule != null ? latestSchedule.getEndingDatetime() : null,
                event.getPrice(),
                event.getOutOfTickets(),
                event.getApproved()
        );
    }

    private Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> resolveLatestSchedules(List<Event> events) {
        List<Integer> eventIds = events.stream()
                .map(Event::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (eventIds.isEmpty()) {
            return Map.of();
        }

        return eventSchedulingRepository.findLatestSchedulesByEventIds(eventIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        EventSchedulingRepository.LatestEventScheduleProjection::getEventId,
                        projection -> projection,
                        (left, right) -> left
                ));
    }

    private static String blankAsNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static YearMonth normalizeMonth(String month) {
        String normalized = blankAsNull(month);
        if (normalized == null) {
            return null;
        }

        try {
            return YearMonth.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro month deve estar no formato yyyy-MM");
        }
    }
}
