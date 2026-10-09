package com.Brafurries.API.event;

import com.Brafurries.API.common.dto.ApiMessageResponse;
import com.Brafurries.API.common.dto.PagedResponseDto;
import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.event.dto.EventDtos;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventSchedulingRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/events")
@Tag(name = "Eventos", description = "Endpoints autenticados de eventos")
public class EventController {
    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventRepository eventRepository;
    private final EventSchedulingRepository eventSchedulingRepository;
    private final EventStaffRepository eventStaffRepository;
    private final EventManagementService eventManagementService;
    private final EventLogoService eventLogoService;
    private final EventApprovalQueueService eventApprovalQueueService;
    private final EventAccessService eventAccessService;
    private final EventTransferService eventTransferService;
    private final EventAvailabilityService eventAvailabilityService;
    private final EventPartnershipService eventPartnershipService;
    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;

    public EventController(EventRepository eventRepository, EventSchedulingRepository eventSchedulingRepository, EventStaffRepository eventStaffRepository, EventManagementService eventManagementService, EventLogoService eventLogoService, EventApprovalQueueService eventApprovalQueueService, EventAccessService eventAccessService, EventTransferService eventTransferService, EventAvailabilityService eventAvailabilityService, EventPartnershipService eventPartnershipService, UserRepository userRepository, UserDiscordRepository userDiscordRepository, UserTelegramRepository userTelegramRepository) {
        this.eventRepository = eventRepository;
        this.eventSchedulingRepository = eventSchedulingRepository;
        this.eventStaffRepository = eventStaffRepository;
        this.eventManagementService = eventManagementService;
        this.eventLogoService = eventLogoService;
        this.eventApprovalQueueService = eventApprovalQueueService;
        this.eventAccessService = eventAccessService;
        this.eventTransferService = eventTransferService;
        this.eventAvailabilityService = eventAvailabilityService;
        this.eventPartnershipService = eventPartnershipService;
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
    }

    @Operation(summary = "Cria um evento")
    @PostMapping("")
    public EventDtos.EventFullDto createEvent(Authentication authentication, @Valid @RequestBody EventDtos.CreateEventRequestDto body) {
        return toFullDto(eventManagementService.createEvent(authentication.getName(), body));
    }

    @Operation(summary = "Detalha evento público por ID")
    @GetMapping("/{id}")
    public EventDtos.EventFullDto getEventById(@PathVariable Integer id) { return toFullDto(eventRepository.findWithLocaleById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"))); }

    @Operation(summary = "Lista eventos para usuário autenticado")
    @GetMapping("")
    public PagedResponseDto<EventDtos.UserEventItemDto> getUserEventsListing(
            Authentication authentication,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "local") String location,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean upcoming
    ) {
        return getUserEventsListingByScope(authentication, page, size, state, location, name, month, weekStart, type, approved, scope, upcoming, false);
    }

    @Operation(summary = "Checa disponibilidade rapida para uma data, localidade e cidade")
    @GetMapping("/availability")
    public EventDtos.EventAvailabilityResponseDto checkEventAvailability(
            @RequestParam Integer localeId,
            @RequestParam String city,
            @RequestParam LocalDate date
    ) {
        String normalizedCity = eventAvailabilityService.normalizeCity(city);
        if (normalizedCity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro city e obrigatorio");
        }
        return eventAvailabilityService.checkAvailability(localeId, normalizedCity, date);
    }

    @Operation(summary = "Lista eventos com escopo partner")
    @GetMapping("/partner")
    public PagedResponseDto<EventDtos.BriefEventItemDto> getPartnerUserEventsListing(
            Authentication authentication,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false, name = "idUsuario") Integer relatedUserId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "local") String location,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(required = false) Boolean upcoming
    ) {
        return getBriefUserEventsListingByScope(authentication, page, size, state, location, name, month, weekStart, type, true, "partner", relatedUserId, upcoming, false);
    }

    @Operation(summary = "Lista eventos com escopo common")
    @GetMapping("/common")
    public PagedResponseDto<EventDtos.BriefEventItemDto> getCommonUserEventsListing(
            Authentication authentication,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false, name = "idUsuario") Integer relatedUserId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "local") String location,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(required = false) Boolean upcoming
    ) {
        return getBriefUserEventsListingByScope(authentication, page, size, state, location, name, month, weekStart, type, true, "common", relatedUserId, upcoming, false);
    }

    @Operation(summary = "Lista eventos gerenciados pela equipe")
    @GetMapping("/managed")
    public PagedResponseDto<EventDtos.BriefEventItemDto> getStaffManagedUserEventsListing(
            Authentication authentication,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false, name = "idUsuario") Integer relatedUserId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "local") String location,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean approved,
            @RequestParam(required = false) Boolean upcoming
    ) {
        return getBriefUserEventsListingByScope(authentication, page, size, state, location, name, month, weekStart, type, true, "managed", relatedUserId, upcoming, true);
    }

    @Operation(summary = "Lista eventos pendentes de aprovação por prioridade")
    @GetMapping("/pending-approval")
    public PagedResponseDto<EventDtos.PendingApprovalEventDto> listPendingApprovalEvents(
            Authentication authentication,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false, name = "idUsuario") Integer relatedUserId
    ) {
        validateRelatedUserAccess(authentication, relatedUserId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Page<EventApprovalQueueService.PendingApprovalEventView> pendingEventsPage = eventApprovalQueueService.listPendingApprovalEvents(
                authentication,
                relatedUserId,
                PageRequest.of(safePage, safeSize)
        );
        List<Event> pageEvents = pendingEventsPage.getContent().stream()
                .map(EventApprovalQueueService.PendingApprovalEventView::event)
                .toList();
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = resolveLatestSchedules(pageEvents);
        List<EventDtos.PendingApprovalEventDto> pageItems = pendingEventsPage.getContent().stream()
                .map(view -> toPendingApprovalDto(
                        view.event(),
                        latestSchedulesByEventId.get(view.event().getId()),
                        view.highlights()
                ))
                .toList();

        return new PagedResponseDto<>(
                pageItems,
                safePage,
                safeSize,
                pendingEventsPage.getTotalElements(),
                pendingEventsPage.getTotalPages(),
                pendingEventsPage.hasNext(),
                pendingEventsPage.hasPrevious()
        );
    }

    @Operation(summary = "Resumo de eventos do membro logado")
    @GetMapping("/minidash")
    public EventDtos.MemberEventMinidashDto getMemberEventMinidash(Authentication authentication) {
        LocalDateTime now = LocalDateTime.now();
        String userEmail = authentication.getName();
        return new EventDtos.MemberEventMinidashDto(
                eventRepository.countActiveApprovedOwnedEvents(userEmail, now),
                eventStaffRepository.countActiveApprovedOrPendingReviewEventsByUserEmail(userEmail, now),
                eventRepository.countPendingReviewOwnedEvents(userEmail)
        );
    }

    @Operation(summary = "Resumo administrativo de eventos")
    @GetMapping("/minidash/admin")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'admin:full', 'events:manage')")
    public EventDtos.AdminEventMinidashDto getAdminEventMinidash() {
        LocalDateTime now = LocalDateTime.now();
        return new EventDtos.AdminEventMinidashDto(
                eventRepository.countActiveApprovedEvents(now),
                eventRepository.countActiveApprovedPartnerEvents(now),
                eventRepository.countPendingApprovalEvents()
        );
    }

    private PagedResponseDto<EventDtos.UserEventItemDto> getUserEventsListingByScope(
            Authentication authentication,
            Integer page,
            Integer size,
            String state,
            String location,
            String name,
            String month,
            String weekStart,
            String type,
            Boolean approved,
            String scope,
            Boolean upcoming,
            boolean staffOnlyManaged
    ) {
        Page<Event> eventsPage = findUserEventsPageByScope(authentication, page, size, state, location, name, month, weekStart, type, approved, scope, null, upcoming, staffOnlyManaged);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String userEmail = authentication.getName();
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = resolveLatestSchedules(eventsPage.getContent());
        Set<Integer> partnerEventIds = eventPartnershipService.findActivePartnerEventIds(eventsPage.getContent().stream().map(Event::getId).toList());
        List<EventDtos.UserEventItemDto> pageItems = eventsPage.getContent().stream()
                .map(event -> toUserEventItemDto(
                        event,
                        latestSchedulesByEventId.get(event.getId()),
                        userEmail,
                        partnerEventIds.contains(event.getId())
                ))
                .toList();

        return new PagedResponseDto<>(
                pageItems,
                safePage,
                safeSize,
                eventsPage.getTotalElements(),
                eventsPage.getTotalPages(),
                eventsPage.hasNext(),
                eventsPage.hasPrevious()
        );
    }

    private PagedResponseDto<EventDtos.BriefEventItemDto> getBriefUserEventsListingByScope(
            Authentication authentication,
            Integer page,
            Integer size,
            String state,
            String location,
            String name,
            String month,
            String weekStart,
            String type,
            Boolean approved,
            String scope,
            Integer relatedUserId,
            Boolean upcoming,
            boolean staffOnlyManaged
    ) {
        validateRelatedUserAccess(authentication, relatedUserId);
        Page<Event> eventsPage = findUserEventsPageByScope(authentication, page, size, state, location, name, month, weekStart, type, approved, scope, relatedUserId, upcoming, staffOnlyManaged);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> latestSchedulesByEventId = resolveLatestSchedules(eventsPage.getContent());
        Map<Integer, List<String>> highlightsByEventId = eventApprovalQueueService.resolveHighlights(eventsPage.getContent());
        Map<Integer, List<String>> permissionsByEventId = resolveEventPermissions(authentication, eventsPage.getContent());
        Set<Integer> partnerEventIds = eventPartnershipService.findActivePartnerEventIds(eventsPage.getContent().stream().map(Event::getId).toList());
        List<EventDtos.BriefEventItemDto> pageItems = eventsPage.getContent().stream()
                .map(event -> toBriefEventItemDto(
                        event,
                        latestSchedulesByEventId.get(event.getId()),
                        highlightsByEventId.getOrDefault(event.getId(), List.of()),
                        permissionsByEventId.getOrDefault(event.getId(), List.of()),
                        partnerEventIds.contains(event.getId())
                ))
                .toList();

        return new PagedResponseDto<>(
                pageItems,
                safePage,
                safeSize,
                eventsPage.getTotalElements(),
                eventsPage.getTotalPages(),
                eventsPage.hasNext(),
                eventsPage.hasPrevious()
        );
    }

    private Page<Event> findUserEventsPageByScope(
            Authentication authentication,
            Integer page,
            Integer size,
            String state,
            String location,
            String name,
            String month,
            String weekStart,
            String type,
            Boolean approved,
            String scope,
            Integer relatedUserId,
            Boolean upcoming,
            boolean staffOnlyManaged
    ) {
        String userEmail = authentication.getName();
        LocalDateTime now = LocalDateTime.now();
        boolean canSeeAllEvents = hasAdminAccess(authentication);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String normalizedState = blankAsNull(state);
        String normalizedLocation = blankAsNull(location);
        String normalizedName = blankAsNull(name);
        String normalizedType = normalizeType(type);
        String normalizedScope = normalizeScope(scope);
        YearMonth normalizedMonth = normalizeMonth(month);
        LocalDateTime monthStartDatetime = normalizedMonth == null ? null : normalizedMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEndDatetime = normalizedMonth == null ? null : normalizedMonth.plusMonths(1).atDay(1).atStartOfDay();
        LocalDate normalizedWeekStart = normalizeWeekStart(weekStart);
        LocalDateTime weekStartDatetime = normalizedWeekStart == null ? null : normalizedWeekStart.atStartOfDay();
        LocalDateTime weekEndDatetime = normalizedWeekStart == null ? null : normalizedWeekStart.plusWeeks(1).atStartOfDay();
        boolean canManageAllEvents = hasAnyPermission(authentication, "admin:full", "events:manage", "events:edit", "events:delete");
        Pageable pageable = PageRequest.of(safePage, safeSize);

        return eventRepository.findUserEventsListing(
                userEmail,
                canSeeAllEvents,
                canManageAllEvents,
                approved,
                normalizedState,
                normalizedLocation,
                normalizedName,
                monthStartDatetime,
                monthEndDatetime,
                weekStartDatetime,
                weekEndDatetime,
                normalizedType,
                normalizedScope,
                relatedUserId,
                staffOnlyManaged,
                upcoming,
                now,
                pageable
        );
    }

    @Operation(summary = "Edita um evento")
    @PatchMapping("/{id}")
    @PreAuthorize("@eventAccessService.canEditEvent(authentication, #id, #body)")
    public EventDtos.EventFullDto editEvent(@PathVariable Integer id, @RequestBody JsonNode body) { return toFullDto(eventManagementService.editEvent(id, body)); }

    @Operation(summary = "Exclui definitivamente um evento")
    @DeleteMapping("/{id}")
    @PreAuthorize("@eventAccessService.canDeleteEvent(authentication, #id)")
    public ApiMessageResponse deleteEvent(@PathVariable Integer id) {
        eventManagementService.deleteEvent(id);
        return new ApiMessageResponse(true, "Evento excluído com sucesso");
    }

    @Operation(summary = "Atualiza a logo do evento")
    @PutMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public EventDtos.EventLogoResponseDto uploadEventLogo(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file
    ) {
        return eventLogoService.uploadEventLogo(id, file);
    }

    @Operation(summary = "Remove a logo do evento")
    @DeleteMapping("/{id}/logo")
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public EventDtos.EventLogoResponseDto deleteEventLogo(@PathVariable Integer id) {
        return eventLogoService.deleteEventLogo(id);
    }

    @Operation(summary = "Adiciona staff ao evento")
    @PostMapping("/{id}/staff")
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public ApiMessageResponse addStaff(@PathVariable Integer id, @RequestBody EventDtos.ManageStaffRequestDto body) { eventManagementService.addStaff(id, body); return new ApiMessageResponse(true, "Staff adicionado com sucesso"); }

    @Operation(summary = "Lista staff do evento")
    @GetMapping("/{id}/staff")
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public List<EventDtos.EventStaffDto> listStaff(@PathVariable Integer id) { return toEventStaffDtos(eventStaffRepository.findByEventId(id)); }

    @Operation(summary = "Edita permissões de um staff do evento")
    @PatchMapping("/{id}/staff/{userId}")
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public ApiMessageResponse editStaff(@PathVariable Integer id, @PathVariable Integer userId, @RequestBody EventDtos.EditStaffRequestDto body) { eventManagementService.editStaff(id, userId, body); return new ApiMessageResponse(true, "Staff atualizado com sucesso"); }

    @Operation(summary = "Remove um staff do evento")
    @DeleteMapping("/{id}/staff/{userId}")
    @PreAuthorize("@eventAccessService.canManageEvent(authentication, #id)")
    public ApiMessageResponse removeStaff(@PathVariable Integer id, @PathVariable Integer userId) { eventManagementService.removeStaff(id, userId); return new ApiMessageResponse(true, "Staff removido com sucesso"); }



    @Operation(summary = "Aprova um evento")
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'admin:full')")
    public EventDtos.EventFullDto approveEvent(@PathVariable Integer id) {
        return toFullDto(eventManagementService.approveEvent(id));
    }

    @Operation(summary = "Reprova um evento")
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'admin:full')")
    public EventDtos.EventFullDto rejectEvent(@PathVariable Integer id) {
        return toFullDto(eventManagementService.rejectEvent(id));
    }

    @Operation(summary = "Adiciona parceria ao evento")
    @PostMapping("/{id}/partner")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'admin:full')")
    public ApiMessageResponse addEventPartnerStatus(@PathVariable Integer id) {
        eventManagementService.addEventPartnerStatus(id);
        return new ApiMessageResponse(true, "Parceria adicionada com sucesso");
    }

    @Operation(summary = "Remove parceria do evento")
    @DeleteMapping("/{id}/partner")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'admin:full')")
    public ApiMessageResponse removeEventPartnerStatus(@PathVariable Integer id) {
        eventManagementService.removeEventPartnerStatus(id);
        return new ApiMessageResponse(true, "Parceria removida com sucesso");
    }

    @Operation(summary = "Agenda ou reagenda evento")
    @PatchMapping("/{id}/schedule")
    @PreAuthorize("@eventAccessService.canManageSchedule(authentication, #id)")
    public EventDtos.EventFullDto scheduleEvent(@PathVariable Integer id, @RequestBody EventDtos.ScheduleEventRequestDto body) { return toFullDto(eventManagementService.schedule(id, body)); }

    @Operation(summary = "Solicita transferência de dono do evento")
    @PostMapping("/{id}/transfer-request")
    public EventDtos.EventTransferRequestDto requestEventTransfer(Authentication authentication, @PathVariable Integer id, @RequestBody EventDtos.CreateEventTransferRequestDto body) {
        return eventTransferService.requestTransfer(id, authentication, body);
    }

    private EventDtos.EventBasicDto toBasicDto(Event event) {
        var latestSchedule = latestScheduleFor(event);
        return new EventDtos.EventBasicDto(event.getId(), event.getEventName(), event.getCity(), event.getLocale().getLocaleAbbrev(), latestSchedule != null ? latestSchedule.getStartingDatetime() : null, latestSchedule != null ? latestSchedule.getEndingDatetime() : null, event.getPrice(), event.getOutOfTickets(), event.getApproved());
    }
    private EventDtos.BriefEventItemDto toBriefEventItemDto(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule, List<String> highlights, List<String> permissions, boolean partnerEvent) {
        return new EventDtos.BriefEventItemDto(event.getId(), toEventHostDto(event), event.getEventName(), event.getCity(), event.getLocale().getLocaleAbbrev(), event.getLocale().getLocaleName(), latestSchedule != null ? latestSchedule.getStartingDatetime() : null, latestSchedule != null ? latestSchedule.getEndingDatetime() : null, event.getIsEvent(), partnerEvent, event.getPrice() == null || event.getPrice() <= 0D, event.getEventLogoUrl(), highlights, permissions);
    }
    private EventDtos.EventFullDto toFullDto(Event event) {
        var latestSchedule = latestScheduleFor(event);
        return new EventDtos.EventFullDto(event.getId(), new EventDtos.EventHostDto(event.getHostUser().getId(), event.getHostUser().getDisplayName()), eventPartnershipService.isPartnerEvent(event.getId()), event.getLocale().getId(), event.getLocale().getLocaleAbbrev(), event.getLocale().getLocaleName(), event.getEventName(), event.getDescription(), event.getCity(), event.getAddress(), event.getPointName(), event.getPrice(), event.getMaxPrice(), event.getPriceConfirmed(), event.getGroupChatLink(), event.getWebsite(), event.getTicketUrl(), latestSchedule != null ? latestSchedule.getStartingDatetime() : null, latestSchedule != null ? latestSchedule.getEndingDatetime() : null, event.getEventLogoUrl(), event.getOutOfTickets(), event.getSalesEnded(), event.getApproved(), event.getIsEvent(), latestSchedule != null ? latestSchedule.getGcalEventId() : null, toPublicEventStaffDtos(eventStaffRepository.findByEventId(event.getId())));
    }
    private EventDtos.PendingApprovalEventDto toPendingApprovalDto(Event event, List<String> highlights) {
        var latestSchedule = latestScheduleFor(event);
        return toPendingApprovalDto(event, latestSchedule, highlights);
    }
    private EventDtos.PendingApprovalEventDto toPendingApprovalDto(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule, List<String> highlights) {
        return new EventDtos.PendingApprovalEventDto(event.getId(), event.getEventName(), event.getCity(), event.getLocale().getLocaleAbbrev(), event.getLocale().getLocaleName(), latestSchedule != null ? latestSchedule.getStartingDatetime() : null, latestSchedule != null ? latestSchedule.getEndingDatetime() : null, event.getIsEvent(), event.getPrice() == null || event.getPrice() <= 0D, resolveEventHostDisplayName(event), event.getEventLogoUrl(), event.getCreatedAt(), highlights);
    }
    private List<EventDtos.EventStaffDto> toEventStaffDtos(List<EventStaff> staffs) {
        return staffs.stream().map(staff -> new EventDtos.EventStaffDto(
                staff.getUser().getId(),
                resolveStaffDisplayName(staff),
                staff.getMngAgenda(),
                staff.getEditEvent(),
                staff.getMngStaff(),
                staff.getCargo()
        )).toList();
    }


    private List<EventDtos.PublicEventStaffDto> toPublicEventStaffDtos(List<EventStaff> staffs) {
        return staffs.stream().map(staff -> new EventDtos.PublicEventStaffDto(
                staff.getUser().getId(),
                resolveStaffDisplayName(staff),
                staff.getCargo()
        )).toList();
    }


    private String resolveStaffDisplayName(EventStaff staff) {
        if (staff.getUser().getDisplayName() != null && !staff.getUser().getDisplayName().isBlank()) return staff.getUser().getDisplayName();

        return userDiscordRepository.findByUser(staff.getUser())
                .map(discord -> discord.getDisplayName() != null && !discord.getDisplayName().isBlank() ? discord.getDisplayName() : discord.getUsername())
                .filter(name -> name != null && !name.isBlank())
                .or(() -> userTelegramRepository.findByUser(staff.getUser())
                        .map(telegram -> telegram.getDisplayName() != null && !telegram.getDisplayName().isBlank() ? telegram.getDisplayName() : telegram.getUsername())
                        .filter(name -> name != null && !name.isBlank()))
                .orElse(staff.getUser().getUsername());
    }

    private String resolveEventHostDisplayName(Event event) {
        if (event.getHostUser() == null) return null;
        if (event.getHostUser().getDisplayName() != null && !event.getHostUser().getDisplayName().isBlank()) return event.getHostUser().getDisplayName();

        return userDiscordRepository.findByUser(event.getHostUser())
                .map(discord -> discord.getDisplayName() != null && !discord.getDisplayName().isBlank() ? discord.getDisplayName() : discord.getUsername())
                .filter(name -> name != null && !name.isBlank())
                .or(() -> userTelegramRepository.findByUser(event.getHostUser())
                        .map(telegram -> telegram.getDisplayName() != null && !telegram.getDisplayName().isBlank() ? telegram.getDisplayName() : telegram.getUsername())
                        .filter(name -> name != null && !name.isBlank()))
                .orElse(event.getHostUser().getUsername());
    }

    private EventDtos.EventHostDto toEventHostDto(Event event) {
        if (event.getHostUser() == null) return null;
        return new EventDtos.EventHostDto(event.getHostUser().getId(), resolveEventHostDisplayName(event));
    }

    private Map<Integer, List<String>> resolveEventPermissions(Authentication authentication, List<Event> events) {
        if (events.isEmpty()) {
            return Map.of();
        }

        List<String> globalPermissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> "ROLE_ADMIN".equals(authority)
                        || "admin:full".equals(authority)
                        || "events:manage".equals(authority)
                        || "events:edit".equals(authority)
                        || "events:delete".equals(authority))
                .distinct()
                .toList();

        Integer userId = userRepository.findByEmail(authentication.getName())
                .map(user -> user.getId())
                .orElse(null);
        Map<Integer, com.Brafurries.API.entity.event.EventStaff> staffByEventId = userId == null
                ? Map.of()
                : eventStaffRepository.findByUserId(userId).stream()
                .filter(staff -> staff.getEvent() != null && staff.getEvent().getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        staff -> staff.getEvent().getId(),
                        Function.identity(),
                        (left, right) -> left
                ));

        Map<Integer, List<String>> permissionsByEventId = new java.util.LinkedHashMap<>();
        for (Event event : events) {
            java.util.LinkedHashSet<String> permissions = new java.util.LinkedHashSet<>(globalPermissions);

            if (event.getHostUser() != null && event.getHostUser().getId() != null && event.getHostUser().getId().equals(userId)) {
                permissions.add("owner");
            }

            var staff = staffByEventId.get(event.getId());
            if (staff != null) {
                if (Boolean.TRUE.equals(staff.getMngAgenda())) permissions.add("mngAgenda");
                if (Boolean.TRUE.equals(staff.getEditEvent())) permissions.add("editEvent");
                if (Boolean.TRUE.equals(staff.getMngStaff())) permissions.add("mngStaff");
            }

            permissionsByEventId.put(event.getId(), List.copyOf(permissions));
        }

        return permissionsByEventId;
    }

    private void validateRelatedUserAccess(Authentication authentication, Integer relatedUserId) {
        if (relatedUserId == null) {
            return;
        }

        Integer loggedUserId = userRepository.findByEmail(authentication.getName())
                .map(user -> user.getId())
                .orElse(null);

        if (loggedUserId != null && loggedUserId.equals(relatedUserId)) {
            return;
        }

        if (!hasAnyPermission(authentication, "ROLE_ADMIN", "admin:full", "events:manage", "events:edit", "events:delete")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão para consultar eventos de outro usuário");
        }
    }

    private String blankAsNull(String value) { return (value == null || value.isBlank()) ? null : value; }

    private EventDtos.UserEventItemDto toUserEventItemDto(Event event, EventSchedulingRepository.LatestEventScheduleProjection latestSchedule, String userEmail, boolean partnerEvent) {
        EventDtos.StaffStatusDto staffStatus = resolveStaffStatus(event, userEmail);
        return new EventDtos.UserEventItemDto(
                event.getId(),
                event.getEventName(),
                event.getCity(),
                event.getLocale().getLocaleAbbrev(),
                latestSchedule != null ? latestSchedule.getStartingDatetime() : null,
                latestSchedule != null ? latestSchedule.getEndingDatetime() : null,
                event.getApproved(),
                partnerEvent,
                staffStatus.isStaff() ? staffStatus : null
        );
    }

    private EventDtos.StaffStatusDto resolveStaffStatus(Event event, String userEmail) {
        boolean isStaff = eventStaffRepository.existsByEventIdAndUserEmail(event.getId(), userEmail);
        if (!isStaff) return new EventDtos.StaffStatusDto(false, false);

        boolean canEdit = eventStaffRepository.existsByEventIdAndUserEmailAndEditEventTrue(event.getId(), userEmail);
        return new EventDtos.StaffStatusDto(true, canEdit);
    }

    private EventSchedulingRepository.LatestEventScheduleProjection latestScheduleFor(Event event) {
        if (event == null || event.getId() == null) {
            return null;
        }
        return eventSchedulingRepository.findLatestSchedulesByEventIds(List.of(event.getId())).stream().findFirst().orElse(null);
    }

    private Map<Integer, EventSchedulingRepository.LatestEventScheduleProjection> resolveLatestSchedules(List<Event> events) {
        List<Integer> eventIds = events.stream()
                .map(Event::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
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

    private boolean hasAdminAccess(Authentication authentication) {
        return hasAnyPermission(authentication, "ROLE_ADMIN", "admin:full");
    }

    private boolean hasAnyPermission(Authentication authentication, String... permissions) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> {
                    for (String permission : permissions) {
                        if (permission.equals(authority)) return true;
                    }
                    return false;
                });
    }

    private YearMonth normalizeMonth(String month) {
        String normalized = blankAsNull(month);
        if (normalized == null) return null;
        try {
            return YearMonth.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro month deve estar no formato yyyy-MM");
        }
    }

    private LocalDate normalizeWeekStart(String weekStart) {
        String normalized = blankAsNull(weekStart);
        if (normalized == null) return null;
        try {
            LocalDate parsed = LocalDate.parse(normalized);
            if (parsed.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro weekStart deve ser uma segunda-feira no formato yyyy-MM-dd");
            }
            return parsed;
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro weekStart deve estar no formato yyyy-MM-dd");
        }
    }

    private String normalizeType(String type) {
        String normalized = blankAsNull(type);
        if (normalized == null) return null;
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!"meet".equals(lowered) && !"evento".equals(lowered)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parâmetro type deve ser 'meet' ou 'evento'");
        }
        return lowered;
    }

    private String normalizeScope(String scope) {
        String normalized = blankAsNull(scope);
        if (normalized == null) return null;
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!"managed".equals(lowered) && !"partner".equals(lowered) && !"common".equals(lowered)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parâmetro scope deve ser 'managed', 'partner' ou 'common'");
        }
        return lowered;
    }

}
