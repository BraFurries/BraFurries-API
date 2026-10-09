package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityActivityControllerTest {
    @Mock CommunityActivityService service;
    @Mock Authentication auth;

    @Test
    void malformedNumericFiltersReturnBadRequestBeforeService() {
        CommunityActivityController controller = new CommunityActivityController(service);

        ResponseStatusException actorError = assertThrows(
            ResponseStatusException.class,
            () -> controller.list(auth, 10, null, "not-a-number", null, null, null, "30")
        );
        assertEquals(HttpStatus.BAD_REQUEST, actorError.getStatusCode());

        ResponseStatusException limitError = assertThrows(
            ResponseStatusException.class,
            () -> controller.list(auth, 10, null, null, null, null, null, "many")
        );
        assertEquals(HttpStatus.BAD_REQUEST, limitError.getStatusCode());

        verifyNoInteractions(service);
    }

    @Test
    void malformedDateFiltersReturnBadRequestBeforeService() {
        CommunityActivityController controller = new CommunityActivityController(service);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> controller.list(auth, 10, null, null, "not-a-date", null, null, "30")
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(service);
    }
}
