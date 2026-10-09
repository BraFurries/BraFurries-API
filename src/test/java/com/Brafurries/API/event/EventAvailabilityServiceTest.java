package com.Brafurries.API.event;

import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventRepository.EventAvailabilityProjection;
import com.Brafurries.API.repository.misc.LocaleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventAvailabilityServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private LocaleRepository localeRepository;

    @Test
    void officialEventWithinSevenDaysButOutsideSameWeekendDoesNotBlockAvailability() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 21, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 13, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 28, 0, 0))
        )).thenReturn(List.of(new Projection("Evento Grande", "Rio de Janeiro", LocalDateTime.of(2026, 6, 25, 10, 0), true, false, false)));

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 20));

        assertTrue(result.localeAvailable());
        assertTrue(result.cityAvailable());
        assertEquals(
                List.of(new EventDtos.NearbyEventDto(
                        "Evento Grande",
                        "Rio de Janeiro",
                        2,
                        "RJ",
                        "Rio de Janeiro",
                        LocalDateTime.of(2026, 6, 25, 10, 0),
                        LocalDateTime.of(2026, 6, 25, 12, 0),
                        "evento"
                )),
                result.nearbyEvents()
        );
    }

    @Test
    void officialEventOnSameWeekendBlocksLocaleAndCityAvailability() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 21, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 13, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 28, 0, 0))
        )).thenReturn(List.of(new Projection("Evento Sabado", "Rio de Janeiro", LocalDateTime.of(2026, 6, 21, 10, 0), true, false, true)));

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 20));

        assertFalse(result.localeAvailable());
        assertFalse(result.cityAvailable());
    }

    @Test
    void meetWithSameLocaleAndSameCityBlocksLocaleAndCityAvailability() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 17, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 18, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 10, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 25, 0, 0))
        )).thenReturn(List.of(new Projection("Meet Local", "São Paulo", LocalDateTime.of(2026, 6, 21, 14, 0), false, true, true)));

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 17));

        assertFalse(result.localeAvailable());
        assertFalse(result.cityAvailable());
        assertEquals("meet", result.nearbyEvents().getFirst().type());
    }

    @Test
    void meetWithSameCityButDifferentLocaleDoesNotConflict() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 17, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 18, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 10, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 25, 0, 0))
        )).thenReturn(List.of(new Projection("Meet Outro Locale", "São Paulo", LocalDateTime.of(2026, 6, 21, 14, 0), false, false, true)));

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 17));

        assertTrue(result.localeAvailable());
        assertTrue(result.cityAvailable());
        assertTrue(result.nearbyEvents().isEmpty());
    }

    @Test
    void meetWithSameLocaleButDifferentCityBlocksOnlyLocaleAvailability() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 17, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 18, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 10, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 25, 0, 0))
        )).thenReturn(List.of(new Projection("Meet Mesmo Locale", "Campinas", LocalDateTime.of(2026, 6, 21, 14, 0), false, true, true)));

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 17));

        assertFalse(result.localeAvailable());
        assertTrue(result.cityAvailable());
        assertEquals(1, result.nearbyEvents().size());
    }

    @Test
    void sundayUsesCurrentWeekendSaturday() {
        when(localeRepository.existsById(1)).thenReturn(true);
        when(eventRepository.findApprovedNearbyEventsForAvailability(
                eq(1),
                eq(LocalDateTime.of(2026, 6, 21, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 20, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 22, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 14, 0, 0)),
                eq(LocalDateTime.of(2026, 6, 29, 0, 0))
        )).thenReturn(List.of());

        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        EventDtos.EventAvailabilityResponseDto result = service.checkAvailability(1, "sao paulo", LocalDate.of(2026, 6, 21));

        assertTrue(result.localeAvailable());
        assertTrue(result.cityAvailable());
    }

    @Test
    void normalizeCityTrimsCollapsesSpacesAndLowercases() {
        EventAvailabilityService service = new EventAvailabilityService(eventRepository, localeRepository);

        assertEquals("sao paulo", service.normalizeCity("  São   Paulo  "));
    }

    private record Projection(
            String eventName,
            String city,
            Integer localeId,
            String localeAbbrev,
            String localeName,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            Boolean isEvent,
            Boolean sameLocale,
            Boolean sameDateOrWeekend
    ) implements EventAvailabilityProjection {

        private Projection(String eventName, String city, LocalDateTime startingDatetime, Boolean isEvent, Boolean sameLocale, Boolean sameDateOrWeekend) {
            this(eventName, city, sameLocale ? 1 : 2, sameLocale ? "SP" : "RJ", sameLocale ? "Sao Paulo" : "Rio de Janeiro", startingDatetime, startingDatetime.plusHours(2), isEvent, sameLocale, sameDateOrWeekend);
        }

        @Override
        public String getEventName() {
            return eventName;
        }

        @Override
        public String getCity() {
            return city;
        }

        @Override
        public Integer getLocaleId() {
            return localeId;
        }

        @Override
        public String getLocaleAbbrev() {
            return localeAbbrev;
        }

        @Override
        public String getLocaleName() {
            return localeName;
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
        public Boolean getIsEvent() {
            return isEvent;
        }

        @Override
        public Boolean getSameLocale() {
            return sameLocale;
        }

        @Override
        public Boolean getSameDateOrWeekend() {
            return sameDateOrWeekend;
        }
    }
}
