package com.Brafurries.API.event;

import static com.Brafurries.API.config.CacheConfig.ADMIN_OVERVIEW;
import static com.Brafurries.API.config.CacheConfig.ADMIN_PARTNERS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_EVENT_CARDS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_MANAGED_EVENT;
import static com.Brafurries.API.config.CacheConfig.EVENT_AVAILABILITY;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.entity.misc.PartnerLink;
import com.Brafurries.API.partner.PartnerSlugService;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.misc.PartnerLinkRepository;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventPartnerManagementService {

    private final PartnerRepository partnerRepository;
    private final PartnerLinkRepository partnerLinkRepository;
    private final EventRepository eventRepository;
    private final PartnerSlugService partnerSlugService;

    public EventPartnerManagementService(
        PartnerRepository partnerRepository,
        PartnerLinkRepository partnerLinkRepository,
        EventRepository eventRepository,
        PartnerSlugService partnerSlugService
    ) {
        this.partnerRepository = partnerRepository;
        this.partnerLinkRepository = partnerLinkRepository;
        this.eventRepository = eventRepository;
        this.partnerSlugService = partnerSlugService;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true),
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    })
    public Partner activateForEvent(Integer eventId) {
        Event event = lockEvent(eventId);
        List<PartnerLink> existingLinks = partnerLinkRepository.findEventPartnerLinks(event.getId());
        if (existingLinks.stream().anyMatch(link -> "active".equalsIgnoreCase(link.getPartner().getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento já possui parceria ativa");
        }
        if (!existingLinks.isEmpty()) {
            Partner reusable = existingLinks.getFirst().getPartner();
            reusable.setStatus("active");
            return partnerRepository.save(reusable);
        }

        Partner partner = new Partner();
        partner.setName(event.getEventName());
        partner.setSlug(partnerSlugService.uniqueSlug(event.getEventName(), null));
        partner.setCategory(Boolean.TRUE.equals(event.getIsEvent()) ? "event" : "meet");
        partner.setStatus("active");
        partner.setDescription(event.getDescription());
        partner.setImageUrl(event.getEventLogoUrl());
        partner.setWebsiteUrl(event.getWebsite());
        if (event.getHostUser() != null) {
            partner.setContactName(firstNonBlank(event.getHostUser().getDisplayName(), event.getHostUser().getUsername()));
        }

        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType("event");
        link.setTargetId(event.getId());
        link.setLabel(event.getEventName());
        partner.getLinks().add(link);
        addExternalLink(partner, "website", event.getWebsite());
        addExternalLink(partner, "chat", event.getGroupChatLink());
        addExternalLink(partner, "tickets", event.getTicketUrl());
        return partnerRepository.save(partner);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true),
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    })
    public void suspendForEvent(Integer eventId) {
        lockEvent(eventId);
        List<Partner> activePartners = partnerLinkRepository.findEventPartnerLinks(eventId).stream()
            .map(PartnerLink::getPartner)
            .filter(partner -> "active".equalsIgnoreCase(partner.getStatus()))
            .distinct()
            .toList();
        if (activePartners.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Evento já não possui parceria ativa");
        }
        activePartners.forEach(partner -> partner.setStatus("suspended"));
        partnerRepository.saveAll(activePartners);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true),
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    })
    public void synchronizeCategory(Integer eventId, boolean isEvent) {
        String category = isEvent ? "event" : "meet";
        List<Partner> changedPartners = partnerLinkRepository.findEventPartnerLinks(eventId).stream()
            .map(PartnerLink::getPartner)
            .distinct()
            .filter(partner -> !category.equalsIgnoreCase(partner.getCategory()))
            .peek(partner -> partner.setCategory(category))
            .toList();
        if (!changedPartners.isEmpty()) {
            partnerRepository.saveAll(changedPartners);
        }
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true),
        @CacheEvict(cacheNames = EVENT_AVAILABILITY, allEntries = true)
    })
    public void detachForDeletedEvent(Integer eventId) {
        List<PartnerLink> links = partnerLinkRepository.findEventPartnerLinks(eventId);
        List<Partner> activePartners = links.stream()
            .map(PartnerLink::getPartner)
            .distinct()
            .filter(partner -> "active".equalsIgnoreCase(partner.getStatus()))
            .peek(partner -> partner.setStatus("suspended"))
            .toList();
        if (!activePartners.isEmpty()) {
            partnerRepository.saveAll(activePartners);
        }
        if (!links.isEmpty()) {
            partnerLinkRepository.deleteAll(links);
            partnerLinkRepository.flush();
        }
    }

    private Event lockEvent(Integer eventId) {
        return eventRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
    }

    private void addExternalLink(Partner partner, String type, String url) {
        if (url == null || url.isBlank()) return;
        PartnerExternalLink link = new PartnerExternalLink();
        link.setPartner(partner);
        link.setType(type);
        link.setUrl(url.trim());
        partner.getExternalLinks().add(link);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

}
