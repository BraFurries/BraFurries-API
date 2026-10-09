package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventScheduling;
import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.event.EventTransferLogRepository;
import com.Brafurries.API.repository.event.EventTransferRequestRepository;
import com.Brafurries.API.repository.misc.LocaleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class EventManagementServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventStaffRepository eventStaffRepository;

    @Mock
    private EventSchedulingRepository eventSchedulingRepository;

    @Mock
    private EventTransferRequestRepository eventTransferRequestRepository;

    @Mock
    private EventTransferLogRepository eventTransferLogRepository;

    @Mock
    private LocaleRepository localeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EventPartnerManagementService eventPartnerManagementService;

    @Mock
    private EventLogoStorageService eventLogoStorageService;

    @Mock
    private UserDiscordRepository userDiscordRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private EventManagementService eventManagementService;

    @Test
    void editEventIgnoresLogoUrlWhenFieldIsPresentInPatchBody() {
        Event event = baseEvent();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventLogoUrl", "https://cdn.example.com/new-logo.webp");
        body.put("isEvent", false);

        when(eventRepository.findByIdForUpdate(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findTopByEventIdOrderByEndingDatetimeDescIdDesc(10)).thenReturn(Optional.empty());
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Event updated = eventManagementService.editEvent(10, body);

        assertEquals("https://cdn.example.com/old-logo.webp", updated.getEventLogoUrl());
        assertFalse(updated.getIsEvent());
    }

    @Test
    void editEventUpdatesScheduleWhenDatetimesArePresentInPatchBody() {
        Event event = baseEvent();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("startingDatetime", "2026-06-20T11:00");
        body.put("endingDatetime", "2026-06-20T20:00");

        when(eventRepository.findWithLocaleById(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findTopByEventIdOrderByEndingDatetimeDescIdDesc(10)).thenReturn(Optional.empty());
        when(eventSchedulingRepository.save(any(EventScheduling.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        eventManagementService.editEvent(10, body);

        verify(eventSchedulingRepository).save(any(EventScheduling.class));
    }

    @Test
    void partnerEndpointsDelegateToPartnerDomainWithoutMutatingEvent() {
        eventManagementService.addEventPartnerStatus(10);
        eventManagementService.removeEventPartnerStatus(10);

        verify(eventPartnerManagementService).activateForEvent(10);
        verify(eventPartnerManagementService).suspendForEvent(10);
    }

    @Test
    void editEventSynchronizesEveryLinkedPartnerCategoryWhenTypeChanges() {
        Event event = baseEvent();
        ObjectNode body = objectMapper.createObjectNode().put("isEvent", false);
        when(eventRepository.findByIdForUpdate(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findTopByEventIdOrderByEndingDatetimeDescIdDesc(10)).thenReturn(Optional.empty());
        when(eventRepository.saveAndFlush(event)).thenReturn(event);

        eventManagementService.editEvent(10, body);

        verify(eventRepository).findByIdForUpdate(10);
        verify(eventPartnerManagementService).synchronizeCategory(10, false);
    }

    @Test
    void editEventDoesNotTouchPartnersWhenTypeDoesNotChange() {
        Event event = baseEvent();
        ObjectNode body = objectMapper.createObjectNode().put("description", "Nova descrição");
        when(eventRepository.findWithLocaleById(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findTopByEventIdOrderByEndingDatetimeDescIdDesc(10)).thenReturn(Optional.empty());
        when(eventRepository.saveAndFlush(event)).thenReturn(event);

        eventManagementService.editEvent(10, body);

        verify(eventPartnerManagementService, never()).synchronizeCategory(any(), any(Boolean.class));
    }

    @Test
    void deleteEventDetachesPartnersBeforeDeletingEventAndDependencies() {
        Event event = baseEvent();
        when(eventRepository.findByIdForUpdate(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findByEventId(10)).thenReturn(List.of());

        eventManagementService.deleteEvent(10);

        var ordered = inOrder(eventPartnerManagementService, eventTransferLogRepository,
            eventTransferRequestRepository, eventSchedulingRepository, eventStaffRepository, eventRepository);
        ordered.verify(eventPartnerManagementService).detachForDeletedEvent(10);
        ordered.verify(eventTransferLogRepository).deleteByEventId(10);
        ordered.verify(eventTransferRequestRepository).deleteByEventId(10);
        ordered.verify(eventSchedulingRepository).deleteByEventId(10);
        ordered.verify(eventStaffRepository).deleteByEventId(10);
        ordered.verify(eventRepository).delete(event);
        verify(eventLogoStorageService).deleteEventLogoByUrl("https://cdn.example.com/old-logo.webp");
    }

    @Test
    void deleteEventWithoutLogoDoesNotTouchStorage() {
        Event event = baseEvent();
        event.setEventLogoUrl(null);
        when(eventRepository.findByIdForUpdate(10)).thenReturn(Optional.of(event));
        when(eventSchedulingRepository.findByEventId(10)).thenReturn(List.of());

        eventManagementService.deleteEvent(10);

        verify(eventLogoStorageService, never()).deleteEventLogoByUrl(any());
        verify(eventRepository).delete(event);
    }

    @Test
    void googleCleanupDeletesEveryConfiguredRemoteScheduleWithOneToken() {
        EventScheduling first = remoteSchedule("calendar-a", "google-1");
        EventScheduling second = remoteSchedule("calendar-b", "google-2");
        EventScheduling localOnly = remoteSchedule(null, "google-3");
        EventManagementService service = spy(eventManagementService);
        ReflectionTestUtils.setField(service, "googleServiceAccountEmail", "service@example.com");
        ReflectionTestUtils.setField(service, "googleServiceAccountPrivateKey", "private-key");
        doReturn("token").when(service).fetchGoogleAccessToken();
        doNothing().when(service).deleteGoogleEvent(any(), any(), any());

        service.cleanupGoogleCalendarEvents(List.of(first, second, localOnly));

        verify(service).fetchGoogleAccessToken();
        verify(service).deleteGoogleEvent("token", "calendar-a", "google-1");
        verify(service).deleteGoogleEvent("token", "calendar-b", "google-2");
        verify(service, never()).deleteGoogleEvent("token", null, "google-3");
    }

    @Test
    void googleCleanupDoesNotBlockWhenIntegrationIsNotConfigured() {
        EventManagementService service = spy(eventManagementService);

        service.cleanupGoogleCalendarEvents(List.of(remoteSchedule("calendar-a", "google-1")));

        verify(service, never()).fetchGoogleAccessToken();
        verify(service, never()).deleteGoogleEvent(any(), any(), any());
    }

    @Test
    void googleDeleteTreatsRemoteNotFoundAsIdempotentSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(eventManagementService, "httpClient", builder.build());
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/calendar-a/events/google-1"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertDoesNotThrow(() -> eventManagementService.deleteGoogleEvent("token", "calendar-a", "google-1"));
        server.verify();
    }

    private EventScheduling remoteSchedule(String calendarId, String googleEventId) {
        EventScheduling schedule = new EventScheduling();
        schedule.setGcalendarId(calendarId);
        schedule.setGcalEventId(googleEventId);
        return schedule;
    }

    private Event baseEvent() {
        Locale locale = new Locale();
        locale.setId(1);
        locale.setLocaleAbbrev("SP");
        locale.setLocaleName("Sao Paulo");

        Event event = new Event();
        event.setId(10);
        event.setLocale(locale);
        event.setEventName("Evento");
        event.setDescription("Descricao");
        event.setCity("Sao Paulo");
        event.setAddress("Endereco");
        event.setPointName("Portao");
        event.setPrice(45D);
        event.setMaxPrice(0D);
        event.setPriceConfirmed(false);
        event.setGroupChatLink("https://t.me/exemplo");
        event.setWebsite("https://example.com");
        event.setTicketUrl("https://tickets.example.com");
        event.setEventLogoUrl("https://cdn.example.com/old-logo.webp");
        event.setOutOfTickets(false);
        event.setSalesEnded(false);
        event.setApproved(true);
        event.setIsEvent(true);
        return event;
    }
}
