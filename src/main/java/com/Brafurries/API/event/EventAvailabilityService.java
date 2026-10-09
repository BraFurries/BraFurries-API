package com.Brafurries.API.event;

import static com.Brafurries.API.config.CacheConfig.EVENT_AVAILABILITY;

import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventRepository.EventAvailabilityProjection;
import com.Brafurries.API.repository.misc.LocaleRepository;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventAvailabilityService {

    private final EventRepository eventRepository;
    private final LocaleRepository localeRepository;

    public EventAvailabilityService(EventRepository eventRepository, LocaleRepository localeRepository) {
        this.eventRepository = eventRepository;
        this.localeRepository = localeRepository;
    }

    @Cacheable(cacheNames = EVENT_AVAILABILITY, key = "#localeId + ':' + #normalizedCity + ':' + #date")
    public EventDtos.EventAvailabilityResponseDto checkAvailability(Integer localeId, String normalizedCity, LocalDate date) {
        if (!localeRepository.existsById(localeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Localidade invalida");
        }

        LocalDateTime dateStart = date.atStartOfDay();
        LocalDateTime dateEnd = date.plusDays(1).atStartOfDay();
        LocalDate weekendStartDate = resolveWeekendStart(date);
        LocalDateTime weekendStart = weekendStartDate.atStartOfDay();
        LocalDateTime weekendEnd = weekendStartDate.plusDays(2).atStartOfDay();
        LocalDateTime eventWindowStart = date.minusDays(7).atStartOfDay();
        LocalDateTime eventWindowEnd = date.plusDays(8).atStartOfDay();

        List<EventAvailabilityProjection> nearbyEvents = eventRepository.findApprovedNearbyEventsForAvailability(
                localeId,
                dateStart,
                dateEnd,
                weekendStart,
                weekendEnd,
                eventWindowStart,
                eventWindowEnd
        ).stream()
                .filter(event -> Boolean.TRUE.equals(event.getIsEvent())
                        || Boolean.TRUE.equals(event.getSameLocale()))
                .toList();

        boolean hasGlobalEventConflict = nearbyEvents.stream()
                .anyMatch(event -> Boolean.TRUE.equals(event.getIsEvent())
                        && Boolean.TRUE.equals(event.getSameDateOrWeekend()));
        boolean hasLocaleConflict = hasGlobalEventConflict || nearbyEvents.stream()
                .anyMatch(event -> !Boolean.TRUE.equals(event.getIsEvent())
                        && Boolean.TRUE.equals(event.getSameLocale()));
        boolean hasCityConflict = hasGlobalEventConflict || nearbyEvents.stream()
                .anyMatch(event -> !Boolean.TRUE.equals(event.getIsEvent())
                        && Boolean.TRUE.equals(event.getSameLocale())
                        && normalizedCity.equals(normalizeCity(event.getCity())));

        return new EventDtos.EventAvailabilityResponseDto(
                date,
                localeId,
                normalizedCity,
                !hasLocaleConflict,
                !hasCityConflict,
                nearbyEvents.stream()
                        .map(event -> new EventDtos.NearbyEventDto(
                                event.getEventName(),
                                event.getCity(),
                                event.getLocaleId(),
                                event.getLocaleAbbrev(),
                                event.getLocaleName(),
                                event.getStartingDatetime(),
                                event.getEndingDatetime(),
                                Boolean.TRUE.equals(event.getIsEvent()) ? "evento" : "meet"
                        ))
                        .toList()
        );
    }

    public String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String collapsed = city.trim().replaceAll("\\s+", " ");
        return Normalizer.normalize(collapsed, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private LocalDate resolveWeekendStart(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        }
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }
}
