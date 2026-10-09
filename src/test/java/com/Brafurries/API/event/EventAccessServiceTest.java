package com.Brafurries.API.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class EventAccessServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventStaffRepository eventStaffRepository;

    @InjectMocks
    private EventAccessService service;

    @Test
    void roleAdminCanDeleteEvent() {
        assertTrue(service.canDeleteEvent(authentication("ROLE_ADMIN"), 10));
    }

    @Test
    void explicitDeleteAuthoritiesCanDeleteEvent() {
        assertTrue(service.canDeleteEvent(authentication("admin:full"), 10));
        assertTrue(service.canDeleteEvent(authentication("events:delete"), 10));
    }

    @Test
    void hostCanDeleteOwnEvent() {
        when(eventRepository.existsByIdAndHostUserEmail(10, "user@example.com")).thenReturn(true);

        assertTrue(service.canDeleteEvent(authentication("events:edit"), 10));
    }

    @Test
    void ordinaryNonHostCannotDeleteEvent() {
        assertFalse(service.canDeleteEvent(authentication("events:edit"), 10));
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
            "user@example.com",
            "ignored",
            List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
