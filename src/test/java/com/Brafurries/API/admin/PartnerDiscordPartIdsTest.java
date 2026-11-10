package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PartnerDiscordPartIdsTest {
    @Test
    void continuationReferencesRoundtripAsOrderedStringSnowflakes() {
        var ids = List.of("123456789012345678", "123456789012345679");
        String serialized = PartnerDiscordService.writePartIds(ids);
        assertEquals("[\"123456789012345678\",\"123456789012345679\"]", serialized);
        assertEquals(ids, PartnerDiscordService.readPartIds(serialized));
        assertEquals(List.of(), PartnerDiscordService.readPartIds("[]"));
    }

    @Test
    void duplicateOrInvalidMessageIdentifiersAreRejected() {
        assertThrows(ResponseStatusException.class, () ->
            PartnerDiscordService.writePartIds(List.of("123", "123")));
        assertThrows(ResponseStatusException.class, () ->
            PartnerDiscordService.readPartIds("[\"not-a-snowflake\"]"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
            PartnerDiscordService.readPartIds("not-json"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
}
