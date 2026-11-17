package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioDetailsDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioReferenceServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioReferenceService service;

    @BeforeEach void setup() {
        service = new CreatorStudioReferenceService(access, jdbc);
    }

    @Test
    void freeOrUnauthorizedAccountsCannotReadPrivateLinks() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.list(auth, 80L)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnsafeOrInvalidReferenceLinksBeforeAnyDataAccess() {
        when(access.requirePro(auth)).thenReturn(31);
        for (String url : List.of("javascript:alert(1)", "http://example.com",
                "https://name:password@example.com/a",
                "https://example.com/#fragment",
                "https://example.com/%ZZ",
                "https://example.com/a\nb")) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(auth, 80L, new ReferenceDraft(null, url)),
                "Must reject: " + url).getStatusCode());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void cannotAddReferencesToACommissionOwnedBySomeoneElse() {
        when(access.requirePro(auth)).thenReturn(31);
        // Parent row with FOR UPDATE is missing for this artist.
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.create(auth, 80L,
                new ReferenceDraft("Pose", "https://example.com/pose.png")))
            .getStatusCode());
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void linkCreationUsesOnlyTheAuthenticatedArtistAndOwnedCommission() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(80L))).thenReturn(List.of(80L));
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class),
            eq(31), eq(80L))).thenReturn(0);
        when(jdbc.queryForObject(contains("MAX(position)"), eq(Integer.class),
            eq(31), eq(80L))).thenReturn(0);
        doAnswer(inv -> {
            KeyHolder keys = inv.getArgument(1);
            keys.getKeyList().add(Map.of("id", 444L));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(jdbc.query(contains("creator_studio_reference_links"),
            any(RowMapper.class), eq(31), eq(80L), eq(444L)))
            .thenReturn(List.of(new Reference(444L, 80L, "Pose", "https://example.com/a", 0)));

        Reference result = service.create(auth, 80L,
            new ReferenceDraft("Pose", "https://example.com/a"));
        assertEquals(444L, result.id());
        verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(80L));
    }

    @Test
    void limitsReferencesWithoutLosingTheExistingLinks() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(80L))).thenReturn(List.of(80L));
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class),
            eq(31), eq(80L))).thenReturn(30);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.create(auth, 80L,
                new ReferenceDraft(null, "https://example.com/ref"))).getStatusCode());
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }
}
