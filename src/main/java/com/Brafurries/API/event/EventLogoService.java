package com.Brafurries.API.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.event.EventLogoStorageService.StoredEventLogo;
import com.Brafurries.API.event.dto.EventDtos.EventLogoResponseDto;
import com.Brafurries.API.repository.event.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventLogoService {

    private static final Logger log = LoggerFactory.getLogger(EventLogoService.class);

    private final EventRepository eventRepository;
    private final EventLogoStorageService eventLogoStorageService;

    public EventLogoService(EventRepository eventRepository, EventLogoStorageService eventLogoStorageService) {
        this.eventRepository = eventRepository;
        this.eventLogoStorageService = eventLogoStorageService;
    }

    @Transactional
    public EventLogoResponseDto uploadEventLogo(Integer eventId, MultipartFile file) {
        Event event = findEvent(eventId);
        String previousLogoUrl = event.getEventLogoUrl();

        StoredEventLogo storedLogo = eventLogoStorageService.uploadEventLogo(event.getId(), file);

        event.setEventLogoUrl(storedLogo.url());
        Event saved = eventRepository.save(event);

        if (previousLogoUrl != null && !previousLogoUrl.isBlank() && !previousLogoUrl.equals(storedLogo.url())) {
            try {
                eventLogoStorageService.deleteEventLogoByUrl(previousLogoUrl);
            } catch (RuntimeException ex) {
                log.warn("Falha ao remover logo anterior do evento {}", event.getId(), ex);
            }
        }

        return new EventLogoResponseDto(saved.getId(), saved.getEventLogoUrl());
    }

    @Transactional
    public EventLogoResponseDto deleteEventLogo(Integer eventId) {
        Event event = findEvent(eventId);
        String previousLogoUrl = event.getEventLogoUrl();

        event.setEventLogoUrl(null);
        Event saved = eventRepository.save(event);

        eventLogoStorageService.deleteEventLogoByUrl(previousLogoUrl);

        return new EventLogoResponseDto(saved.getId(), saved.getEventLogoUrl());
    }

    private Event findEvent(Integer eventId) {
        return eventRepository.findById(eventId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evento não encontrado"));
    }
}
