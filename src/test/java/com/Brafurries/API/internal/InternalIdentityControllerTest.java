package com.Brafurries.API.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.internal.dto.InternalIdentityDtos.InternalIdentityResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class InternalIdentityControllerTest {

    private final InternalIdentityService service = mock(InternalIdentityService.class);
    private final InternalIdentityController controller = new InternalIdentityController(service, "service-token");

    @Test
    void rejectsMissingOrInvalidServiceToken() {
        assertThrows(ResponseStatusException.class, () -> controller.getByUserId(null, 1, 2));
        assertThrows(ResponseStatusException.class, () -> controller.getByUserId("Bearer wrong", 1, 2));
    }

    @Test
    void acceptsConfiguredServiceTokenAndKeepsCommunityContext() {
        InternalIdentityResponse expected = mock(InternalIdentityResponse.class);
        when(service.getByDiscordUserId(777L, 50)).thenReturn(expected);

        var result = controller.getByDiscordUserId("Bearer service-token", 777L, 50);

        assertSame(expected, result);
        verify(service).getByDiscordUserId(777L, 50);
    }
}
