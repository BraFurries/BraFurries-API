package com.Brafurries.API.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerLink;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.partner.PartnerSlugService;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.misc.PartnerLinkRepository;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EventPartnerManagementServiceTest {

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private PartnerLinkRepository partnerLinkRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private PartnerSlugService partnerSlugService;

    @InjectMocks
    private EventPartnerManagementService service;

    @Test
    void createsCompletePartnerForEventWithoutChangingEvent() {
        Event event = event(true);
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of());
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Partner partner = activate(event);

        assertEquals("event", partner.getCategory());
        assertEquals("active", partner.getStatus());
        assertEquals("Patas 2026", partner.getName());
        assertEquals("Descricao", partner.getDescription());
        assertEquals("https://cdn.example/logo.png", partner.getImageUrl());
        assertEquals("https://example.com", partner.getWebsiteUrl());
        assertEquals("Host", partner.getContactName());
        assertNull(partner.getContactEmail());
        assertEquals("patas-2026", partner.getSlug());
        PartnerLink link = partner.getLinks().iterator().next();
        assertEquals("event", link.getTargetType());
        assertEquals(20, link.getTargetId());
        assertEquals(List.of("chat", "tickets", "website"), partner.getExternalLinks().stream()
            .map(item -> item.getType()).sorted().toList());
        assertEquals("Patas 2026", event.getEventName());
    }

    @Test
    void createsMeetCategoryAndSkipsEmptyLinks() {
        Event event = event(false);
        event.setWebsite(null);
        event.setGroupChatLink(" ");
        event.setTicketUrl(null);
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of());
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Partner partner = activate(event);

        assertEquals("meet", partner.getCategory());
        assertTrue(partner.getExternalLinks().isEmpty());
    }

    @Test
    void activePartnerIsConflictAndDoesNotCreateDuplicate() {
        Partner active = partner(9, "active", "Customizado");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(active)));

        Event event = event(true);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> activate(event));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(partnerRepository, never()).save(any());
    }

    @Test
    void suspendedPartnerIsReactivatedWithoutOverwritingCustomFields() {
        Partner suspended = partner(9, "suspended", "Nome customizado");
        suspended.setDescription("Descricao customizada");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(suspended)));
        when(partnerRepository.save(suspended)).thenReturn(suspended);

        Partner result = activate(event(true));

        assertEquals("active", result.getStatus());
        assertEquals("Nome customizado", result.getName());
        assertEquals("Descricao customizada", result.getDescription());
    }

    @Test
    void pendingPartnerIsReusedDeterministicallyFromNewestLink() {
        Partner older = partner(8, "pending", "Antigo");
        Partner newest = partner(9, "suspended", "Novo");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(newest), link(older)));
        when(partnerRepository.save(newest)).thenReturn(newest);

        Partner result = activate(event(true));

        assertEquals(9, result.getId());
        assertEquals("active", result.getStatus());
        assertEquals("pending", older.getStatus());
    }

    @Test
    void removeSuspendsEveryActivePartnerWithoutDeletingAnything() {
        Partner first = partner(8, "active", "Primeiro");
        Partner second = partner(9, "active", "Segundo");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(second), link(first)));

        suspend(20);

        assertEquals("suspended", first.getStatus());
        assertEquals("suspended", second.getStatus());
        verify(partnerRepository).saveAll(List.of(second, first));
        verify(partnerRepository, never()).delete(any());
        verify(partnerLinkRepository, never()).delete(any());
    }

    @Test
    void removeWithoutActivePartnerIsConflict() {
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(partner(9, "suspended", "Suspenso"))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> suspend(20));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void createSuspendReactivateCycleReusesTheSamePartner() {
        Event event = event(true);
        java.util.concurrent.atomic.AtomicReference<Partner> savedPartner = new java.util.concurrent.atomic.AtomicReference<>();
        when(partnerLinkRepository.findEventPartnerLinks(20))
            .thenReturn(List.of())
            .thenAnswer(invocation -> List.of(link(savedPartner.get())))
            .thenAnswer(invocation -> List.of(link(savedPartner.get())));
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> {
            Partner partner = invocation.getArgument(0);
            savedPartner.set(partner);
            return partner;
        });

        when(eventRepository.findByIdForUpdate(20)).thenReturn(Optional.of(event));
        when(partnerSlugService.uniqueSlug("Patas 2026", null)).thenReturn("patas-2026");
        Partner created = service.activateForEvent(20);
        service.suspendForEvent(20);
        Partner reactivated = service.activateForEvent(20);

        assertEquals(created, reactivated);
        assertEquals("active", reactivated.getStatus());
        verify(partnerRepository).saveAll(List.of(created));
    }

    @Test
    void eventToMeetNormalizesAllHistoricalPartnersWithoutChangingCustomFields() {
        Partner active = customizedPartner(8, "active", "event");
        Partner suspended = customizedPartner(9, "suspended", "event");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(suspended), link(active)));

        service.synchronizeCategory(20, false);

        assertEquals("meet", active.getCategory());
        assertEquals("meet", suspended.getCategory());
        assertCustomFieldsPreserved(active);
        assertCustomFieldsPreserved(suspended);
        verify(partnerRepository).saveAll(List.of(suspended, active));
    }

    @Test
    void meetToEventNormalizesPartnerCategory() {
        Partner pending = customizedPartner(9, "pending", "meet");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(pending)));

        service.synchronizeCategory(20, true);

        assertEquals("event", pending.getCategory());
        assertCustomFieldsPreserved(pending);
    }

    @Test
    void unchangedCategoryDoesNotWritePartner() {
        Partner partner = customizedPartner(9, "active", "event");
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(link(partner)));

        service.synchronizeCategory(20, true);

        verify(partnerRepository, never()).saveAll(any());
    }

    @Test
    void deletingEventSuspendsActivePartnerAndRemovesOnlyInternalLinks() {
        Partner active = customizedPartner(8, "active", "event");
        Partner suspended = customizedPartner(9, "suspended", "event");
        var activeLink = link(active);
        var suspendedLink = link(suspended);
        int activeExternalLinks = active.getExternalLinks().size();
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of(activeLink, suspendedLink));

        service.detachForDeletedEvent(20);

        assertEquals("suspended", active.getStatus());
        assertEquals("suspended", suspended.getStatus());
        assertEquals(activeExternalLinks, active.getExternalLinks().size());
        verify(partnerRepository).saveAll(List.of(active));
        verify(partnerRepository, never()).delete(any());
        verify(partnerLinkRepository).deleteAll(List.of(activeLink, suspendedLink));
        verify(partnerLinkRepository).flush();
    }

    @Test
    void deletingEventWithoutPartnersDoesNotAttemptPartnerWrites() {
        when(partnerLinkRepository.findEventPartnerLinks(20)).thenReturn(List.of());

        service.detachForDeletedEvent(20);

        verify(partnerRepository, never()).saveAll(any());
        verify(partnerLinkRepository, never()).deleteAll(any());
    }

    private Partner activate(Event event) {
        when(eventRepository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        lenient().when(partnerSlugService.uniqueSlug(event.getEventName(), null)).thenReturn("patas-2026");
        return service.activateForEvent(event.getId());
    }

    private void suspend(Integer eventId) {
        when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event(true)));
        service.suspendForEvent(eventId);
    }

    private Partner customizedPartner(int id, String status, String category) {
        Partner partner = partner(id, status, "Nome customizado");
        partner.setCategory(category);
        partner.setDescription("Descrição customizada");
        partner.setImageUrl("https://custom.example/logo.png");
        partner.setWebsiteUrl("https://custom.example");
        partner.setContactName("Contato customizado");
        partner.setContactEmail("contato@example.com");
        partner.setSlug("slug-customizado-" + id);
        com.Brafurries.API.entity.misc.PartnerExternalLink externalLink = new com.Brafurries.API.entity.misc.PartnerExternalLink();
        externalLink.setPartner(partner);
        externalLink.setType("website");
        externalLink.setUrl("https://external.example");
        partner.getExternalLinks().add(externalLink);
        return partner;
    }

    private void assertCustomFieldsPreserved(Partner partner) {
        assertEquals("Nome customizado", partner.getName());
        assertEquals("Descrição customizada", partner.getDescription());
        assertEquals("https://custom.example/logo.png", partner.getImageUrl());
        assertEquals("https://custom.example", partner.getWebsiteUrl());
        assertEquals("Contato customizado", partner.getContactName());
        assertEquals("contato@example.com", partner.getContactEmail());
        assertEquals("slug-customizado-" + partner.getId(), partner.getSlug());
        assertEquals(1, partner.getExternalLinks().size());
    }

    private Event event(boolean isEvent) {
        User host = new User();
        host.setDisplayName("Host");
        host.setUsername("host-user");
        host.setEmail("private@example.com");
        Event event = new Event();
        event.setId(20);
        event.setIsEvent(isEvent);
        event.setEventName("Patas 2026");
        event.setDescription("Descricao");
        event.setEventLogoUrl("https://cdn.example/logo.png");
        event.setWebsite("https://example.com");
        event.setGroupChatLink("https://chat.example");
        event.setTicketUrl("https://tickets.example");
        event.setHostUser(host);
        return event;
    }

    private Partner partner(int id, String status, String name) {
        Partner partner = new Partner();
        partner.setId(id);
        partner.setStatus(status);
        partner.setName(name);
        return partner;
    }

    private PartnerLink link(Partner partner) {
        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType("event");
        link.setTargetId(20);
        return link;
    }
}
