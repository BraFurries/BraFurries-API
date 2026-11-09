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

    private void pendingClaim(long claimId,int artistId,int applicantId) {
        when(jdbc.queryForList(contains("FROM commerce_artist_claim_requests WHERE id=?"),
            eq(claimId))).thenReturn(List.of(Map.of(
                "id",claimId,"artist_user_id",artistId,
                "requester_user_id",applicantId,"status","PENDING")));
        when(jdbc.queryForList(contains("FROM commerce_artist_profiles"),
            eq((long)artistId))).thenReturn(List.of(Map.of("user_id",artistId)));
    }

    @Test
    void verifiedExternalArtistClaimLinksOnlyApplicantAfterOwnerApproval() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        pendingClaim(31L,101,77);
        when(jdbc.queryForList(contains("SELECT id,email_verified_at,merged_into_user_id"),
            eq(77))).thenReturn(List.of(Map.of(
                "id",77,"email_verified_at",java.time.LocalDateTime.of(2026,11,9,10,30))));
        when(jdbc.queryForList(contains("SELECT email,password_hash,merged_into_user_id"),
            eq(101))).thenReturn(List.of(Map.of()));
        when(jdbc.update(contains("UPDATE commerce_artist_claim_requests"),
            any(),any(),any(),any())).thenReturn(1);
        when(jdbc.update(contains("UPDATE commerce_artist_profiles"),
            any(),any(),any())).thenReturn(1);

        artists.approve(auth,31L,new ApproveClaim("TELEGRAM",
            "Confirmação independente pelo contato previamente conhecido"));

        verify(jdbc).update(contains("UPDATE commerce_artist_profiles"),
            eq(77),eq(2),eq(101));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTIST_CLAIM"),eq(31L),eq("APPROVED"));
        verify(jdbc).queryForList(argThat(sql->sql.contains("user_social_identities")
            && sql.contains("FOR UPDATE")),eq(101));
    }

    @Test
    void doesNotApproveClaimOnExistingDiscordOrTelegramLinkedUser() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        pendingClaim(32L,102,77);
        when(jdbc.queryForList(contains("SELECT id,email_verified_at,merged_into_user_id"),
            eq(77))).thenReturn(List.of(Map.of(
                "id",77,"email_verified_at",java.time.LocalDateTime.of(2026,11,9,10,30))));
        when(jdbc.queryForList(contains("SELECT email,password_hash,merged_into_user_id"),
            eq(102))).thenReturn(List.of(Map.of()));
        when(jdbc.queryForList(contains("SELECT id FROM user_discord"),eq(102)))
            .thenReturn(List.of(Map.of("id",10)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> artists.approve(auth,32L,new ApproveClaim(
                "TELEGRAM","Confirmado externamente"))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_claim_requests"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_profiles"),
            any(Object[].class));
    }

    @Test
    void cannotClaimUserWithPreviouslyIssuedLoginOrRecoveryToken() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        pendingClaim(34L,104,77);
        when(jdbc.queryForList(contains("SELECT id,email_verified_at,merged_into_user_id"),
            eq(77))).thenReturn(List.of(Map.of(
                "id",77,"email_verified_at",java.time.LocalDateTime.of(2026,11,9,10,30))));
        when(jdbc.queryForList(contains("SELECT email,password_hash,merged_into_user_id"),
            eq(104))).thenReturn(List.of(Map.of()));
        when(jdbc.queryForList(contains("SELECT id FROM user_refresh_tokens"),eq(104)))
            .thenReturn(List.of(Map.of("id",20)));

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> artists.approve(auth,34L,new ApproveClaim(
                "IN_PERSON","Confirmado pessoalmente"))).getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_claim_requests"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("UPDATE commerce_artist_profiles"),
            any(Object[].class));
    }

    @Test
    void simultaneousApprovalMustUpdateExactlyOneOwnershipLink() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        pendingClaim(33L,103,77);
        when(jdbc.queryForList(contains("SELECT id,email_verified_at,merged_into_user_id"),
            eq(77))).thenReturn(List.of(Map.of(
                "id",77,"email_verified_at",java.time.LocalDateTime.of(2026,11,9,10,30))));
        when(jdbc.queryForList(contains("SELECT email,password_hash,merged_into_user_id"),
            eq(103))).thenReturn(List.of(Map.of()));
        when(jdbc.update(contains("UPDATE commerce_artist_claim_requests"),
            any(),any(),any(),any())).thenReturn(1);
        // The status changed, but a concurrent operation prevented the
        // owner-link update. The transaction must fail and roll back both.
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->artists.approve(auth,33L,new ApproveClaim(
                "IN_PERSON","Identidade confirmada pessoalmente"))).getStatusCode());
        verify(jdbc,never()).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTIST_CLAIM"),eq(33L),eq("APPROVED"));
    }

    @Test
    void ownerRevocationClearsAccessButPreservesOriginalApprovalEvidence() {
        when(authorization.requireOwner(auth)).thenReturn(user(2));
        when(jdbc.queryForList(contains("FROM commerce_artist_profiles"),
            eq(101L))).thenReturn(List.of(Map.of(
                "user_id",101,"claimed_by_user_id",77)));
        when(jdbc.queryForList(contains("SELECT id FROM commerce_artist_claim_requests"),
            eq(101L),eq(77))).thenReturn(List.of(Map.of("id",31L)));
        when(jdbc.update(contains("UPDATE commerce_artist_claim_requests"),
            any(),any(),any())).thenReturn(1);
        // Concrete numeric matchers select JdbcTemplate.update(String,Object...)
        // rather than the overloaded update(String,Object[],int[]).
        when(jdbc.update(contains("UPDATE commerce_artist_profiles"),
            eq(101L),eq(77))).thenReturn(1);

        artists.revoke(auth,101,new RevokeClaim("Pedido expresso da artista"));

        verify(jdbc).update(argThat(sql->
            sql.contains("revocation_reason") && sql.contains("revoked_by_user_id")
            && !sql.contains("reviewed_by_user_id") && !sql.contains("verification_method")),
            eq("Pedido expresso da artista"),eq(2),eq(31L));
        verify(jdbc).update(contains("UPDATE commerce_artist_profiles"),
            eq(101L),eq(77));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTIST_CLAIM"),eq(31L),eq("REVOKED"));
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
