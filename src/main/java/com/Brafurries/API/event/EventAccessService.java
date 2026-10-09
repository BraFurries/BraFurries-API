package com.Brafurries.API.event;

import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class EventAccessService {
    private static final String[] EVENT_EDIT_PERMISSIONS = {
        "admin:full",
        "events:manage",
        "events:edit",
        "events:delete"
    };

    private final EventRepository eventRepository;
    private final EventStaffRepository eventStaffRepository;

    public EventAccessService(EventRepository eventRepository, EventStaffRepository eventStaffRepository) {
        this.eventRepository = eventRepository;
        this.eventStaffRepository = eventStaffRepository;
    }

    public boolean canManageEvent(Authentication authentication, Integer eventId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (hasAnyPermission(authentication, EVENT_EDIT_PERMISSIONS)) {
            return true;
        }

        String email = authentication.getName();
        boolean isHost = eventRepository.existsByIdAndHostUserEmail(eventId, email);
        if (isHost) {
            return true;
        }

        return eventStaffRepository.existsByEventIdAndUserEmailAndEditEventTrue(eventId, email);
    }

    public boolean canManageSchedule(Authentication authentication, Integer eventId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (hasAnyPermission(authentication, EVENT_EDIT_PERMISSIONS)) {
            return true;
        }

        String email = authentication.getName();
        boolean isHost = eventRepository.existsByIdAndHostUserEmail(eventId, email);
        if (isHost) {
            return true;
        }

        return eventStaffRepository.existsByEventIdAndUserEmailAndMngAgendaTrue(eventId, email);
    }

    public boolean canDeleteEvent(Authentication authentication, Integer eventId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAnyPermission(authentication, "ROLE_ADMIN", "admin:full", "events:delete")) {
            return true;
        }
        return eventRepository.existsByIdAndHostUserEmail(eventId, authentication.getName());
    }

    public boolean canEditEvent(Authentication authentication, Integer eventId, JsonNode body) {
        boolean wantsScheduleChange = body != null
                && !body.isNull()
                && (body.has("startingDatetime") || body.has("endingDatetime"));

        if (wantsScheduleChange && !canManageSchedule(authentication, eventId)) {
            return false;
        }

        return canManageEvent(authentication, eventId);
    }

    private boolean hasAnyPermission(Authentication authentication, String... permissions) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority -> {
                for (String permission : permissions) {
                    if (permission.equals(authority)) {
                        return true;
                    }
                }
                return false;
            });
    }
}
