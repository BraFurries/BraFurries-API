package com.Brafurries.API.event;

import static com.Brafurries.API.config.CacheConfig.DASHBOARD_EVENT_CARDS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_MANAGED_EVENT;
import static com.Brafurries.API.config.CacheConfig.EVENT_AVAILABILITY;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventScheduling;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.event.EventTransferLogRepository;
import com.Brafurries.API.repository.event.EventTransferRequestRepository;
import com.Brafurries.API.repository.misc.LocaleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventManagementService {

    private static final String BRAZIL_TIMEZONE = "America/Sao_Paulo";
    private static final ZoneId BRAZIL_ZONE_ID = ZoneId.of(BRAZIL_TIMEZONE);

    private final EventRepository eventRepository;
    private final EventStaffRepository eventStaffRepository;
    private final EventSchedulingRepository eventSchedulingRepository;
    private final EventTransferRequestRepository eventTransferRequestRepository;
    private final EventTransferLogRepository eventTransferLogRepository;
    private final LocaleRepository localeRepository;
    private final UserRepository userRepository;
    private final EventPartnerManagementService eventPartnerManagementService;
    private final EventLogoStorageService eventLogoStorageService;
    private final RestClient httpClient;

    @Value("${google_calendar_events_id:}")
    private String googleCalendarId;

    @Value("${google_calendar_pending_events_id:}")
    private String googlePendingCalendarId;

    @Value("${google_calendar_rejected_events_id:}")
    private String googleRejectedCalendarId;

    @Value("${google_token_uri:https://oauth2.googleapis.com/token}")
    private String googleTokenUri;

    @Value("${google_service_account_email:}")
    private String googleServiceAccountEmail;

    @Value("${google_service_account_private_key:}")
    private String googleServiceAccountPrivateKey;

    public EventManagementService(
            EventRepository eventRepository,
            EventStaffRepository eventStaffRepository,
            EventSchedulingRepository eventSchedulingRepository,
            EventTransferRequestRepository eventTransferRequestRepository,
            EventTransferLogRepository eventTransferLogRepository,
            LocaleRepository localeRepository,
            UserRepository userRepository,
            EventPartnerManagementService eventPartnerManagementService,
            EventLogoStorageService eventLogoStorageService
    ) {
        this.eventRepository = eventRepository;
        this.eventStaffRepository = eventStaffRepository;
        this.eventSchedulingRepository = eventSchedulingRepository;
        this.eventTransferRequestRepository = eventTransferRequestRepository;
        this.eventTransferLogRepository = eventTransferLogRepository;
        this.localeRepository = localeRepository;
        this.userRepository = userRepository;
        this.eventPartnerManagementService = eventPartnerManagementService;
        this.eventLogoStorageService = eventLogoStorageService;
        this.httpClient = RestClient.builder().build();
    }

    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public Event createEvent(String userEmail, EventDtos.CreateEventRequestDto body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisição é obrigatório");

        User hostUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));

        if (body.localeId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "localeId é obrigatório");
        Locale locale = localeRepository.findById(body.localeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Localidade inválida"));

        if (body.eventName() == null || body.eventName().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventName é obrigatório");
        if (body.city() == null || body.city().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "city é obrigatório");
        if (body.address() == null || body.address().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "address é obrigatório");
        if (body.startingDatetime() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startingDatetime é obrigatório");
        if (body.endingDatetime() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endingDatetime é obrigatório");
        if (!body.endingDatetime().isAfter(body.startingDatetime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endingDatetime deve ser maior que startingDatetime");
        }

        double safePrice = body.price() == null ? 0D : body.price();
        double safeMaxPrice = body.maxPrice() == null ? 0D : body.maxPrice();

        Event event = new Event();
        event.setHostUser(hostUser);
        event.setLocale(locale);
        event.setEventName(body.eventName().trim());
        event.setDescription(body.description());
        event.setCity(body.city().trim());
        event.setAddress(body.address().trim());
        event.setPointName(body.pointName());
        event.setPrice(safePrice);
        event.setMaxPrice(safeMaxPrice);
        event.setPriceConfirmed(Boolean.TRUE.equals(body.priceConfirmed()));
        event.setGroupChatLink(body.groupChatLink());
        event.setWebsite(body.website());
        event.setTicketUrl(body.ticketUrl());
        event.setCreatedAt(LocalDateTime.now());
        event.setOutOfTickets(Boolean.TRUE.equals(body.outOfTickets()));
        event.setSalesEnded(Boolean.TRUE.equals(body.salesEnded()));
        event.setApproved(null);
        event.setIsEvent(body.isEvent());

        Event savedEvent = eventRepository.save(event);
        createSchedule(savedEvent, body.startingDatetime(), body.endingDatetime());

        EventStaff ownerStaff = new EventStaff();
        ownerStaff.setEvent(savedEvent);
        ownerStaff.setUser(hostUser);
        ownerStaff.setMngAgenda(true);
        ownerStaff.setEditEvent(true);
        ownerStaff.setMngStaff(true);
        ownerStaff.setCargo("Dono");
        eventStaffRepository.save(ownerStaff);

        return savedEvent;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public Event editEvent(Integer id, JsonNode body) {
        if (body == null || body.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisição é obrigatório");
        }
        Event event = body.has("isEvent")
            ? eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"))
            : findEvent(id);
        Boolean previousApproved = event.getApproved();
        Boolean previousIsEvent = event.getIsEvent();

        if (body.has("localeId") && !body.get("localeId").isNull()) {
            Locale locale = localeRepository.findById(body.get("localeId").asInt())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Localidade inválida"));
            event.setLocale(locale);
        }

        if (body.has("eventName")) event.setEventName(textOrNull(body.get("eventName")));
        if (body.has("description")) event.setDescription(textOrNull(body.get("description")));
        if (body.has("city")) event.setCity(textOrNull(body.get("city")));
        if (body.has("address")) event.setAddress(textOrNull(body.get("address")));
        if (body.has("pointName")) event.setPointName(textOrNull(body.get("pointName")));
        if (body.has("price")) event.setPrice(doubleOrNull(body.get("price")));
        if (body.has("maxPrice")) event.setMaxPrice(doubleOrNull(body.get("maxPrice")));
        if (body.has("priceConfirmed")) event.setPriceConfirmed(booleanOrNull(body.get("priceConfirmed")));
        if (body.has("groupChatLink")) event.setGroupChatLink(textOrNull(body.get("groupChatLink")));
        if (body.has("website")) event.setWebsite(textOrNull(body.get("website")));
        if (body.has("ticketUrl")) event.setTicketUrl(textOrNull(body.get("ticketUrl")));
        if (body.has("outOfTickets")) event.setOutOfTickets(booleanOrNull(body.get("outOfTickets")));
        if (body.has("salesEnded")) event.setSalesEnded(booleanOrNull(body.get("salesEnded")));
        if (body.has("isEvent")) event.setIsEvent(booleanOrNull(body.get("isEvent")));
        if (body.has("startingDatetime") || body.has("endingDatetime")) {
            upsertEventSchedule(
                    event,
                    parseScheduleDatetime(textOrNull(body.get("startingDatetime")), "startingDatetime"),
                    parseScheduleDatetime(textOrNull(body.get("endingDatetime")), "endingDatetime")
            );
        }
        syncScheduledEventCalendarIfNeeded(event, previousApproved);

        Event savedEvent = eventRepository.saveAndFlush(event);
        if (!Objects.equals(previousIsEvent, savedEvent.getIsEvent())) {
            eventPartnerManagementService.synchronizeCategory(savedEvent.getId(), Boolean.TRUE.equals(savedEvent.getIsEvent()));
        }
        return savedEvent;
    }

    @Transactional
    public void addStaff(Integer eventId, EventDtos.ManageStaffRequestDto body) {
        Event event = findEvent(eventId);
        User user = resolveStaffUser(body);

        eventStaffRepository.findByEventIdAndUserId(eventId, user.getId()).ifPresentOrElse(
                existing -> applyStaffPermissions(existing, body.mngAgenda(), body.editEvent(), body.mngStaff(), body.cargo(), false),
                () -> {
                    EventStaff staff = new EventStaff();
                    staff.setEvent(event);
                    staff.setUser(user);
                    applyStaffPermissions(staff, body.mngAgenda(), body.editEvent(), body.mngStaff(), body.cargo(), false);
                    eventStaffRepository.save(staff);
                }
        );
    }

    @Transactional
    public void editStaff(Integer eventId, Integer userId, EventDtos.EditStaffRequestDto body) {
        EventStaff staff = eventStaffRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff não encontrado no evento"));
        applyStaffPermissions(staff, body.mngAgenda(), body.editEvent(), body.mngStaff(), body.cargo(), true);
    }

    @Transactional
    public void removeStaff(Integer eventId, Integer userId) {
        Event event = findEvent(eventId);
        if (event.getHostUser() != null && Objects.equals(event.getHostUser().getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não é permitido remover o dono/host do evento");
        }

        EventStaff staff = eventStaffRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff não encontrado no evento"));
        eventStaffRepository.delete(staff);
    }

    private void applyStaffPermissions(EventStaff staff, Boolean mngAgenda, Boolean editEvent, Boolean mngStaff, String cargo, boolean preserveOmittedPermissions) {
        if (!preserveOmittedPermissions || mngAgenda != null) staff.setMngAgenda(Boolean.TRUE.equals(mngAgenda));
        if (!preserveOmittedPermissions || editEvent != null) staff.setEditEvent(Boolean.TRUE.equals(editEvent));
        if (!preserveOmittedPermissions || mngStaff != null) staff.setMngStaff(Boolean.TRUE.equals(mngStaff));
        if (cargo != null) {
            staff.setCargo(cargo.isBlank() ? null : cargo.trim());
        }
    }




    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public Event approveEvent(Integer eventId) {
        Event event = findEvent(eventId);
        if (Boolean.TRUE.equals(event.getApproved())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento já está aprovado");
        }

        Boolean previousApproved = event.getApproved();
        event.setApproved(true);
        syncScheduledEventCalendarIfNeeded(event, previousApproved);
        return eventRepository.save(event);
    }

    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public Event rejectEvent(Integer eventId) {
        Event event = findEvent(eventId);
        if (Boolean.FALSE.equals(event.getApproved())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento já está reprovado");
        }

        Boolean previousApproved = event.getApproved();
        event.setApproved(false);
        syncScheduledEventCalendarIfNeeded(event, previousApproved);
        return eventRepository.save(event);
    }

    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public void addEventPartnerStatus(Integer eventId) {
        eventPartnerManagementService.activateForEvent(eventId);
    }

    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public void removeEventPartnerStatus(Integer eventId) {
        eventPartnerManagementService.suspendForEvent(eventId);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public void deleteEvent(Integer eventId) {
        Event event = eventRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
        String eventLogoUrl = event.getEventLogoUrl();
        java.util.List<EventScheduling> schedules = eventSchedulingRepository.findByEventId(eventId);
        cleanupGoogleCalendarEvents(schedules);
        eventPartnerManagementService.detachForDeletedEvent(eventId);
        eventTransferLogRepository.deleteByEventId(eventId);
        eventTransferRequestRepository.deleteByEventId(eventId);
        eventSchedulingRepository.deleteByEventId(eventId);
        eventStaffRepository.deleteByEventId(eventId);
        eventRepository.delete(event);
        if (eventLogoUrl != null && !eventLogoUrl.isBlank()) {
            eventLogoStorageService.deleteEventLogoByUrl(eventLogoUrl);
        }
    }
    @Transactional
    @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    public Event schedule(Integer eventId, EventDtos.ScheduleEventRequestDto body) {
        Event event = findEvent(eventId);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisição é obrigatório");
        }

        return upsertEventSchedule(
                event,
                parseScheduleDatetime(body.newStartingDatetime(), "newStartingDatetime"),
                parseScheduleDatetime(body.newEndingDatetime(), "newEndingDatetime")
        );
    }

    private LocalDateTime parseScheduleDatetime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalizedValue = value.trim();
        DateTimeFormatter[] formatters = new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(normalizedValue, formatter);
            } catch (DateTimeParseException ignored) {
                // Tenta o próximo formato suportado
            }
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                fieldName + " deve estar em formato ISO-8601 (ex.: 2026-06-20T11:00 ou 2026-06-20T11:00:00)"
        );
    }

    private Event upsertEventSchedule(Event event, LocalDateTime newStart, LocalDateTime newEnd) {
        if (newStart == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newStartingDatetime é obrigatório");
        }
        if (newEnd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newEndingDatetime é obrigatório");
        }
        if (!newEnd.isAfter(newStart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newEndingDatetime deve ser maior que newStartingDatetime");
        }

        EventScheduling latestSchedule = eventSchedulingRepository
                .findTopByEventIdOrderByEndingDatetimeDescIdDesc(event.getId())
                .orElse(null);

        boolean shouldCreateNewSchedule = latestSchedule == null || isCurrentOrPast(latestSchedule);
        if (shouldCreateNewSchedule) {
            createSchedule(event, newStart, newEnd);
            return event;
        }

        updateFutureSchedule(event, latestSchedule, newStart, newEnd);
        return event;
    }

    private boolean isCurrentOrPast(EventScheduling schedule) {
        LocalDateTime now = LocalDateTime.now();
        boolean isInProgress = !now.isBefore(schedule.getStartingDatetime()) && !now.isAfter(schedule.getEndingDatetime());
        boolean isPast = now.isAfter(schedule.getEndingDatetime());
        return isInProgress || isPast;
    }

    private EventScheduling createSchedule(Event event, LocalDateTime newStart, LocalDateTime newEnd) {
        String gcalEventId = null;
        String calendarId = resolveGoogleCalendarId(event.getApproved());
        if (isGoogleCalendarIntegrationConfigured(calendarId)) {
            String accessToken = fetchGoogleAccessToken();
            Map<String, Object> payload = buildGoogleCalendarPayload(event, newStart, newEnd);
            gcalEventId = createGoogleEvent(accessToken, calendarId, payload);
        }

        EventScheduling scheduling = new EventScheduling();
        scheduling.setEvent(event);
        scheduling.setStartingDatetime(newStart);
        scheduling.setEndingDatetime(newEnd);
        scheduling.setGcalendarId(calendarId);
        scheduling.setGcalEventId(gcalEventId);
        return eventSchedulingRepository.save(scheduling);
    }

    private void updateFutureSchedule(Event event, EventScheduling schedule, LocalDateTime newStart, LocalDateTime newEnd) {
        String calendarId = resolveGoogleCalendarId(event.getApproved());
        if (isGoogleCalendarIntegrationConfigured(calendarId)) {
            String accessToken = fetchGoogleAccessToken();
            Map<String, Object> payload = buildGoogleCalendarPayload(event, newStart, newEnd);
            String scheduledCalendarId = schedule.getGcalendarId();
            if (scheduledCalendarId == null || scheduledCalendarId.isBlank()) {
                scheduledCalendarId = calendarId;
                schedule.setGcalendarId(calendarId);
            }

            if (!Objects.equals(scheduledCalendarId, calendarId) && schedule.getGcalEventId() != null && !schedule.getGcalEventId().isBlank()) {
                moveGoogleEventToCalendar(accessToken, event, schedule, calendarId, payload);
            } else if (schedule.getGcalEventId() == null || schedule.getGcalEventId().isBlank()) {
                String newGcalEventId = createGoogleEvent(accessToken, calendarId, payload);
                schedule.setGcalendarId(calendarId);
                schedule.setGcalEventId(newGcalEventId);
            } else {
                updateGoogleEvent(accessToken, calendarId, schedule.getGcalEventId(), payload);
            }
        }

        schedule.setStartingDatetime(newStart);
        schedule.setEndingDatetime(newEnd);
        eventSchedulingRepository.save(schedule);
    }

    private Map<String, Object> buildGoogleCalendarPayload(Event event, LocalDateTime start, LocalDateTime end) {
        return Map.of(
                "summary", event.getEventName(),
                "location", event.getAddress() == null ? "" : event.getAddress(),
                "description", createGCalendarDescription(
                        event.getPrice(),
                        event.getMaxPrice(),
                        event.getGroupChatLink(),
                        event.getWebsite()
                ),
                "colorId", event.getPrice() != null && event.getPrice() == 0D ? "7" : "3",
                "start", Map.of("dateTime", formatGoogleCalendarDatetime(start), "timeZone", BRAZIL_TIMEZONE),
                "end", Map.of("dateTime", formatGoogleCalendarDatetime(end), "timeZone", BRAZIL_TIMEZONE)
        );
    }

    private String createGoogleEvent(String accessToken, String calendarId, Map<String, Object> payload) {
        Map response;
        try {
            response = httpClient.post()
                    .uri("https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events", calendarId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            throw googleIntegrationException("criar evento no Google Calendar", ex);
        }

        if (response == null || !(response.get("id") instanceof String createdId) || createdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao criar evento no Google Calendar");
        }
        return createdId;
    }

    private void updateGoogleEvent(String accessToken, String calendarId, String gcalId, Map<String, Object> payload) {
        try {
            httpClient.put()
                    .uri("https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events/{eventId}", calendarId, gcalId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw googleIntegrationException("atualizar evento no Google Calendar", ex);
        }
    }


    private boolean isGoogleCalendarIntegrationConfigured(String calendarId) {
        return calendarId != null && !calendarId.isBlank()
                && googleServiceAccountEmail != null && !googleServiceAccountEmail.isBlank()
                && googleServiceAccountPrivateKey != null && !googleServiceAccountPrivateKey.isBlank();
    }

    private String resolveGoogleCalendarId(Boolean approved) {
        if (approved == null) {
            return googlePendingCalendarId;
        }
        return Boolean.TRUE.equals(approved) ? googleCalendarId : googleRejectedCalendarId;
    }

    String fetchGoogleAccessToken() {
        if (googleServiceAccountEmail == null || googleServiceAccountEmail.isBlank()
                || googleServiceAccountPrivateKey == null || googleServiceAccountPrivateKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Credenciais Google Service Account ausentes. Configure google_service_account_email e google_service_account_private_key"
            );
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        formData.add("assertion", buildServiceAccountAssertion());

        Map response;
        try {
            response = httpClient.post()
                    .uri(googleTokenUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(formData)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            throw googleIntegrationException("obter access_token do Google", ex);
        }

        if (response == null || !(response.get("access_token") instanceof String token) || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao obter access_token do Google");
        }

        return token;
    }

    private String buildServiceAccountAssertion() {
        try {
            String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            long nowEpochSeconds = Instant.now().getEpochSecond();

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", googleServiceAccountEmail);
            claims.put("scope", "https://www.googleapis.com/auth/calendar");
            claims.put("aud", googleTokenUri);
            claims.put("iat", nowEpochSeconds);
            claims.put("exp", nowEpochSeconds + 3600);

            String payloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(claims);
            String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String unsignedToken = encodedHeader + "." + encodedPayload;
            String signature = signJwt(unsignedToken, googleServiceAccountPrivateKey);
            return unsignedToken + "." + signature;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falha ao gerar assertion JWT da Service Account", ex);
        }
    }

    private String formatGoogleCalendarDatetime(LocalDateTime value) {
        return value.atZone(BRAZIL_ZONE_ID).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private void syncScheduledEventCalendarIfNeeded(Event event, Boolean previousApproved) {
        String currentCalendarId = resolveGoogleCalendarId(event.getApproved());

        EventScheduling latestSchedule = eventSchedulingRepository
                .findTopByEventIdOrderByEndingDatetimeDescIdDesc(event.getId())
                .orElse(null);
        if (latestSchedule == null || latestSchedule.getGcalEventId() == null || latestSchedule.getGcalEventId().isBlank() || isPast(latestSchedule)) {
            return;
        }

        String previousCalendarId = latestSchedule.getGcalendarId();
        if ((previousCalendarId == null || previousCalendarId.isBlank()) && previousApproved != null) {
            previousCalendarId = resolveGoogleCalendarId(previousApproved);
        }
        if (previousCalendarId == null || previousCalendarId.isBlank() || Objects.equals(previousCalendarId, currentCalendarId)) {
            latestSchedule.setGcalendarId(currentCalendarId);
            return;
        }
        if (!isGoogleCalendarIntegrationConfigured(previousCalendarId) || !isGoogleCalendarIntegrationConfigured(currentCalendarId)) {
            return;
        }

        String accessToken = fetchGoogleAccessToken();
        Map<String, Object> payload = buildGoogleCalendarPayload(event, latestSchedule.getStartingDatetime(), latestSchedule.getEndingDatetime());
        moveGoogleEventToCalendar(accessToken, event, latestSchedule, currentCalendarId, payload);
        eventSchedulingRepository.save(latestSchedule);
    }

    private boolean isPast(EventScheduling schedule) {
        return LocalDateTime.now().isAfter(schedule.getEndingDatetime());
    }

    void deleteGoogleEvent(String accessToken, String calendarId, String gcalId) {
        try {
            httpClient.delete()
                    .uri("https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events/{eventId}", calendarId, gcalId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return;
            }
            throw googleIntegrationException("remover evento do Google Calendar", ex);
        }
    }

    void cleanupGoogleCalendarEvents(java.util.List<EventScheduling> schedules) {
        java.util.List<EventScheduling> remoteSchedules = schedules == null ? java.util.List.of() : schedules.stream()
            .filter(schedule -> schedule.getGcalendarId() != null && !schedule.getGcalendarId().isBlank())
            .filter(schedule -> schedule.getGcalEventId() != null && !schedule.getGcalEventId().isBlank())
            .filter(schedule -> isGoogleCalendarIntegrationConfigured(schedule.getGcalendarId()))
            .toList();
        if (remoteSchedules.isEmpty()) {
            return;
        }
        String accessToken = fetchGoogleAccessToken();
        remoteSchedules.forEach(schedule -> deleteGoogleEvent(
            accessToken,
            schedule.getGcalendarId(),
            schedule.getGcalEventId()
        ));
    }

    private void moveGoogleEventToCalendar(String accessToken, Event event, EventScheduling schedule, String targetCalendarId, Map<String, Object> payload) {
        String previousCalendarId = schedule.getGcalendarId();
        String previousGcalEventId = schedule.getGcalEventId();
        String newGcalEventId = createGoogleEvent(accessToken, targetCalendarId, payload);

        schedule.setGcalendarId(targetCalendarId);
        schedule.setGcalEventId(newGcalEventId);

        try {
            deleteGoogleEvent(accessToken, previousCalendarId, previousGcalEventId);
        } catch (ResponseStatusException ex) {
            schedule.setGcalendarId(previousCalendarId);
            schedule.setGcalEventId(previousGcalEventId);
            throw ex;
        }
    }

    private ResponseStatusException googleIntegrationException(String action, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String message = (responseBody == null || responseBody.isBlank())
                ? ex.getStatusText()
                : responseBody.replaceAll("\\s+", " ").trim();
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Falha ao " + action + ": " + message,
                ex
        );
    }

    private String signJwt(String unsignedToken, String privateKeyPem) throws Exception {
        String normalizedPem = privateKeyPem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] privateKeyBytes = Base64.getDecoder().decode(normalizedPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(signature.sign());
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String createGCalendarDescription(Double price, Double maxPrice, String groupLink, String website) {
        double safePrice = price == null ? 0D : price;
        String formattedPrice = formatBrazilianPrice(safePrice);

        StringBuilder description = new StringBuilder(
                safePrice > 0 ? "<strong>>> Evento Pago <<</strong>" : "<strong>>> Evento Gratuito <<</strong>"
        );

        if (maxPrice != null && maxPrice != 0D) {
            String formattedMaxPrice = formatBrazilianPrice(maxPrice);
            description.append("\n\n<strong>Preço:</strong>\nR$")
                    .append(formattedPrice)
                    .append(" a R$")
                    .append(formattedMaxPrice);
        } else if (safePrice > 0) {
            description.append("\n\n<strong>Preço:</strong>\nR$")
                    .append(formattedPrice);
        }

        if (groupLink != null && !Objects.equals(groupLink, "None")) {
            description.append("\n\n<strong>Chat do Evento:</strong>\n<a href=\"")
                    .append(groupLink)
                    .append("\">")
                    .append(groupLink)
                    .append("</a>");
        }

        if (website != null && !Objects.equals(website, "None")) {
            description.append("\n\n<strong>Site:</strong>\n<a href=\"")
                    .append(website)
                    .append("\">")
                    .append(website)
                    .append("</a>");
        }

        return description.toString();
    }

    private String formatBrazilianPrice(Double value) {
        return String.format("%,.2f", value)
                .replace(",", "x")
                .replace(".", ",")
                .replace("x", ".");
    }

    private User resolveStaffUser(EventDtos.ManageStaffRequestDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Corpo da requisicao e obrigatorio");
        }

        if (body.userId() != null) {
            return userRepository.findById(body.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario nao encontrado pelo userId"));
        }

        if (body.email() != null && !body.email().isBlank()) {
            return userRepository.findByEmail(body.email())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado pelo email"));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe userId ou email");
    }

    private Event findEvent(Integer id) {
        return eventRepository.findWithLocaleById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Double doubleOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asDouble();
    }

    private Boolean booleanOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }

}
