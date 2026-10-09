package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AdminBotStreamControllerTest {
    @Test
    void resumesFromLatestAuthenticatedCursor() {
        var service = mock(AdminBotLiveStreamService.class);
        Authentication admin = mock(Authentication.class);
        when(service.subscribe("INFO", 20L, admin)).thenReturn(new SseEmitter());
        var controller = new AdminBotStreamController(service);
        assertEquals(HttpStatus.OK,
            controller.stream("INFO", 9L, "20", admin).getStatusCode());
        verify(service).subscribe("INFO", 20L, admin);
    }

    @Test
    void rejectsInvalidClientResumeCursor() {
        var service = mock(AdminBotLiveStreamService.class);
        var controller = new AdminBotStreamController(service);
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.stream("INFO", null, "-10", null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(service);
    }
}
