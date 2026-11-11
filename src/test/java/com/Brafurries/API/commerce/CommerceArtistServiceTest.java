package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceArtistServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock CommerceFinanceSupport finance;
    @Mock Authentication auth;

    CommerceArtistService artists;

    @BeforeEach
    void setup() { artists=new CommerceArtistService(authorization,jdbc,finance); }

    private User user(int id) {
        User u=new User(); u.setId(id); return u;
    }

    @Test
    void refusesPersonalPortfolioWhenPlatformNotConfigured() {
        when(authorization.isConfigured()).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.portfolio(auth,2026,11)).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void neverCreatesExternalArtistWithoutArtworkPermission() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.registerExternal(auth,new RegisterExternalArtist(
                "Artista Telegram","@art",null,null,null))).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void createsInertPlaceholderWithoutEmailOrPassword() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE)).thenReturn(
            new PlatformAuthorizationService.Actor(user(2),
                new PlatformAuthorizationService.Access(true,true,Set.of())));
        when(finance.insert(eq("INSERT INTO users (display_name) VALUES (?)"),anyString()))
            .thenReturn(1005L);
        Created created=artists.registerExternal(auth,
            new RegisterExternalArtist("Artista Telegram","@art",null,
                "contact@example.invalid",null));
        assertEquals(1005L,created.id());
        verify(finance).insert(eq("INSERT INTO users (display_name) VALUES (?)"),
            eq("Artista Telegram"));
        verify(jdbc).update(contains("INSERT INTO commerce_artist_profiles"),
            eq(1005),eq("Artista Telegram"),eq("@art"),isNull(),
            eq("contact@example.invalid"),isNull(),eq(2));
    }

    @Test
    void personalRoyaltiesFilterOnlyByTheAuthenticatedCanonicalUser() {
        when(authorization.isConfigured()).thenReturn(true);
        when(authorization.currentUser(auth)).thenReturn(user(77));
        Portfolio result=artists.portfolio(auth,2026,11);
        assertEquals(BigDecimal.ZERO,result.accruedTotal());
        verify(jdbc).queryForList(argThat(q->q.contains("FROM commerce_artworks a")
            && q.contains("WHERE a.artist_user_id=?")
            && !q.contains("claimed_by_user_id") && !q.contains("buyer_label")),
            eq(2026),eq(11),eq(77));
        verify(jdbc).queryForList(argThat(q->q.contains("FROM commerce_settlements s")
            && q.contains("WHERE s.artist_user_id=?")),
            eq(77));
    }

    @Test
    void artistEligibilityUsesOnlyCanonicalUserAndRecognizesProfileWithoutRoyalties() {
        when(authorization.isConfigured()).thenReturn(true);
        when(authorization.currentUser(auth)).thenReturn(user(77));
        when(jdbc.queryForObject(contains("commerce_artist_profiles"),eq(Integer.class),
            eq(77),eq(77),eq(77),eq(77))).thenReturn(1);

        assertTrue(artists.myAccess(auth).eligible());
        verify(jdbc).queryForObject(argThat(q ->
                q.contains("p.user_id = ?")
                && q.contains("a.artist_user_id = ?")
                && q.contains("s.artist_user_id = ?")
                && q.contains("r.artist_user_id = ?")
                && !q.contains("claimed_by_user_id")),
            eq(Integer.class),eq(77),eq(77),eq(77),eq(77));
    }

    @Test
    void artistEligibilityIsFalseForOrdinaryMemberWithNoLinkedRecords() {
        when(authorization.isConfigured()).thenReturn(true);
        when(authorization.currentUser(auth)).thenReturn(user(55));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),
            eq(55),eq(55),eq(55),eq(55))).thenReturn(0);
        assertFalse(artists.myAccess(auth).eligible());
    }

    @Test
    void artistEligibilityRejectsMergedAccountAndDoesNotTouchDatabase() {
        when(authorization.isConfigured()).thenReturn(true);
        User alias=user(77);
        alias.setMergedIntoUser(user(99));
        when(authorization.currentUser(auth)).thenReturn(alias);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.myAccess(auth)).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void rejectsCanonicalAliasSessions() {
        when(authorization.isConfigured()).thenReturn(true);
        User alias=user(77);
        alias.setMergedIntoUser(user(99));
        when(authorization.currentUser(auth)).thenReturn(alias);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.portfolio(auth,2026,11)).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }
}
