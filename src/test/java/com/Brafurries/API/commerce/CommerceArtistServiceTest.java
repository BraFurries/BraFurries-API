package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.util.HashMap;
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
    void setup() {
        artists=new CommerceArtistService(authorization,jdbc,finance);
    }

    private User user(int id) {
        User u=new User();
        u.setId(id);
        return u;
    }

    private void authenticatedUser(int id) {
        when(authorization.isConfigured()).thenReturn(true);
        when(authorization.currentUser(auth)).thenReturn(user(id));
    }

    @Test
    void anonymousOrUnconfiguredPortalFailsClosedBeforeAnyFinancialRead() {
        when(authorization.isConfigured()).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            ()->artists.portfolio(auth,2026,11)).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void cannotCreateAnExternalArtistWithoutPlatformArtworkPermission() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            ()->artists.registerExternal(auth,new RegisterExternalArtist(
                "Artista Telegram","@art",null,null,null))).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void createsOnlySparseUsersWithSeparateUntrustedCommercialContact() {
        when(authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE)).thenReturn(
            new PlatformAuthorizationService.Actor(user(2),
                new PlatformAuthorizationService.Access(true,true,Set.of())));
        when(finance.insert(contains("INSERT INTO users (display_name)"),anyString()))
            .thenReturn(1005L);
        Created row=artists.registerExternal(auth,new RegisterExternalArtist(
            "Artista que mora no Telegram","@artista","@instagram",
            "artist-contact@example.invalid","Observação interna"));
        assertEquals(1005L,row.id());
        verify(finance).insert(eq("INSERT INTO users (display_name) VALUES (?)"),
            eq("Artista que mora no Telegram"));
        verify(jdbc).update(contains("INSERT INTO commerce_artist_profiles"),
            eq(1005),eq("Artista que mora no Telegram"),eq("@artista"),
            eq("@instagram"),eq("artist-contact@example.invalid"),
            eq("Observação interna"),eq(2));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTIST"),eq(1005L),eq("CREATED"));
    }

    @Test
    void userCanReadOnlyOwnUserIdAndOwnerApprovedClaimsNotArbitraryId() {
        authenticatedUser(77);
        Portfolio portfolio=artists.portfolio(auth,2026,11);
        assertEquals(2026,portfolio.year());
        assertEquals(BigDecimal.ZERO,portfolio.accruedTotal());
        assertEquals(BigDecimal.ZERO,portfolio.paidTotal());
        verify(jdbc).queryForList(
            argThat(sql->sql.contains("FROM commerce_artworks a") &&
                sql.contains("p.claimed_by_user_id=?") &&
                sql.contains("a.artist_user_id=?") &&
                !sql.contains("buyer_label") && !sql.contains("signed_storage_key")),
            eq(2026),eq(11),eq(77),eq(77));
        verify(jdbc).queryForList(
            argThat(sql->sql.contains("FROM commerce_settlements s") &&
                sql.contains("p.claimed_by_user_id=?") &&
                !sql.contains("external_reference")),
            eq(77),eq(77));
    }

    @Test
    void cannotSelfApproveClaimByInvokingOwnerEndpoint() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.approve(auth,23,new ApproveClaim(
                "TELEGRAM","Confirmação independente"))).getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }

    @Test
    void approvalRequiresIndependentVerificationRecord() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->artists.approve(auth,23,new ApproveClaim("TELEGRAM","")))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsApplicantWithoutConfirmedEmailBeforeGrantingArtistData() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        when(jdbc.queryForList(contains("FROM commerce_artist_claim_requests WHERE id=?"),
            eq(23L))).thenReturn(List.of(Map.of(
                "id",23L,"artist_user_id",101,"requester_user_id",77,"status","PENDING")));
        Map<String,Object> profile=new HashMap<>();
        profile.put("user_id",101);
        profile.put("claimed_by_user_id",null);
        when(jdbc.queryForList(contains("FROM commerce_artist_profiles"),
            eq(101L))).thenReturn(List.of(profile));
        when(jdbc.queryForList(contains("SELECT id,email_verified_at,merged_into_user_id"),
            eq(77))).thenReturn(List.of(Map.of("id",77)));

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->artists.approve(auth,23,new ApproveClaim("TELEGRAM","Verificado")))
            .getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_profiles"),any(Object[].class));
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_claim_requests"),any(Object[].class));
    }

    @Test
    void neverAllowsAnApplicantToReadAllPendingClaims() {
        when(authorization.requireOwner(auth))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->artists.pendingClaims(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void requesterCannotClaimItsOwnArtistUser() {
        authenticatedUser(44);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->artists.requestClaim(auth,new RequestClaim(44,"Minha arte")))
            .getStatusCode());
        verifyNoInteractions(jdbc,finance);
    }
}
