package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.auth.common.AuthService;
import com.Brafurries.API.auth.social.SparseAccountOwnershipGuard;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness=Strictness.LENIENT)
class CommerceArtistInvitationServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock CommerceFinanceSupport finance;
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock AuthService localAuth;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock SparseAccountOwnershipGuard ownership;
    @Mock Authentication auth;
    CommerceArtistInvitationService service;
    static final String TOKEN="a".repeat(64), CODE="ABCDEF123456";

    @BeforeEach
    void setup() {
        service=new CommerceArtistInvitationService(authorization,jdbc,finance,
            users,passwords,localAuth,refreshTokens,ownership);
    }

    private static User user(int id,String email) {
        User u=new User();u.setId(id);u.setEmail(email);u.setAuthVersion(0L);
        return u;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }

    private void invite(int artistId,String hashOfCode) {
        when(jdbc.queryForList(contains("FROM commerce_artist_invitations"),
            eq(hash(TOKEN)))).thenReturn(List.of(Map.of(
                "id",31L,"artist_user_id",artistId,"code_hash",hashOfCode,
                "status","ACTIVE","failed_attempts",0,"not_expired",1
            )));
        when(jdbc.update(contains("UPDATE commerce_artist_invitations"),
            any(),any())).thenReturn(1);
    }

    @Test
    void onlyPlatformOwnerMayGenerateCommercialInvitations() {
        when(authorization.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.issue(auth,new IssueInvitation(10))).getStatusCode());
        verifyNoInteractions(jdbc,finance,users);
    }

    @Test
    void staffIssuerCreatesOnlyHashedSingleUseDualProof() {
        when(authorization.requireOwner(auth)).thenReturn(user(2,"owner@example.invalid"));
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(user(10,null)));
        when(jdbc.queryForList(contains("FROM commerce_artist_profiles"),
            eq(10))).thenReturn(List.of(Map.of("user_id",10)));
        when(finance.insert(contains("INSERT INTO commerce_artist_invitations"),
            any(),any(),any(),any())).thenReturn(31L);
        when(jdbc.queryForObject(contains("DATE_FORMAT(expires_at"),
            eq(String.class),eq(31L))).thenReturn("2026-11-11T12:00:00");

        IssuedInvitation result=service.issue(auth,new IssueInvitation(10));
        assertEquals(31L,result.id());
        assertEquals(64,result.token().length());
        assertEquals(12,result.confirmationCode().length());
        assertNotEquals(result.token(),result.confirmationCode());
        verify(finance).insert(contains("INSERT INTO commerce_artist_invitations"),
            eq(10),eq(hash(result.token())),eq(hash(result.confirmationCode())),eq(2));
        verify(jdbc).update(contains("INSERT INTO commerce_catalog_audit"),
            eq(2),eq("ARTIST_INVITE"),eq(31L),eq("ISSUED"));
        verify(jdbc,never()).update(contains("INSERT INTO user_social_identities"),
            any(Object[].class));
    }

    @Test
    void failedSeparateCodeIncrementsDurableAttemptCounterWithoutActivatingLogin() {
        invite(10,hash("000000000000"));
        assertThrows(CommerceArtistInvitationService.InvalidProof.class,
            ()->service.activate(new ActivateInvitation(
                TOKEN,CODE,"artist@example.invalid","Password123",
                "Password123",true)));
        verify(jdbc).update(contains("SET failed_attempts=failed_attempts+1"),
            eq(31L));
        verifyNoInteractions(users,passwords,localAuth,finance);
    }

    @Test
    void newArtistIsActivatedOnOriginalUserIdAndMustConfirmLocalEmail() {
        invite(10,hash(CODE));
        User artist=user(10,null);
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(artist));
        when(users.findByEmail("chosen@example.invalid")).thenReturn(Optional.empty());
        when(passwords.encode("Password123")).thenReturn("bcrypt-hash");
        when(jdbc.update(contains("SET status='USED'"),eq(10),eq(31L)))
            .thenReturn(1);
        when(finance.insert(contains("INSERT INTO commerce_artist_consolidations"),
            any(),any(),any())).thenReturn(70L);

        InvitationResult result=service.activate(new ActivateInvitation(
            TOKEN,CODE,"chosen@example.invalid","Password123","Password123",true));
        assertEquals("VERIFY_EMAIL",result.status());
        assertEquals("chosen@example.invalid",artist.getEmail());
        assertEquals("bcrypt-hash",artist.getPasswordHash());
        assertNull(artist.getEmailVerifiedAt());
        verify(users).saveAndFlush(artist);
        verify(localAuth).requireLocalEmailConfirmation(artist);
        verify(finance).insert(contains("INSERT INTO commerce_artist_consolidations"),
            eq(31L),eq(10),eq(10));
        verify(ownership).assertOnlyArtistCommerceReferences(10);
    }

    private void existingAccount(boolean overlap) {
        invite(10,hash(CODE));
        when(authorization.currentUser(auth)).thenReturn(user(20,"verified@example.invalid"));
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(user(10,null)));
        User authenticated=user(20,"verified@example.invalid");
        authenticated.setPasswordHash("existing-bcrypt-hash");
        authenticated.setEmailVerifiedAt(LocalDateTime.of(2026,11,9,10,0));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(authenticated));
        when(passwords.matches("correct-password","existing-bcrypt-hash")).thenReturn(true);
        when(jdbc.queryForList(contains("FROM commerce_artist_profiles"),
            eq(10),eq(20))).thenReturn(List.of(Map.of("user_id",10)));
        if(overlap) {
            when(jdbc.queryForList(contains("FROM commerce_royalty_accruals"),
                eq(10),eq(20),eq(10),eq(20))).thenReturn(List.of(
                    Map.of("artist_user_id",10,"period_year",2026,"period_month",11),
                    Map.of("artist_user_id",20,"period_year",2026,"period_month",11)));
        }
    }

    @Test
    void failsClosedOnRealFinancialConflictBeforeTransferringAnyRights() {
        existingAccount(true);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->service.redeem(auth,new RedeemInvitation(TOKEN,CODE,"correct-password")));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(jdbc,never()).update(contains("UPDATE commerce_artworks"),
            any(Object[].class));
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void authenticatedVerifiedExistingAccountReceivesAtomicCanonicalOwnership() {
        existingAccount(false);
        when(jdbc.update(contains("UPDATE commerce_artist_profiles"),
            eq(20),eq(10))).thenReturn(1);
        when(jdbc.update(contains("SET status='USED'"),eq(20),eq(31L)))
            .thenReturn(1);
        when(finance.insert(contains("INSERT INTO commerce_artist_consolidations"),
            any(),any(),any(),any(),any(),any(),any())).thenReturn(70L);

        InvitationResult result=service.redeem(auth,
            new RedeemInvitation(TOKEN,CODE,"correct-password"));
        assertEquals("RELOGIN_REQUIRED",result.status());
        verify(jdbc).update(contains("UPDATE commerce_artworks SET artist_user_id"),
            eq(20),eq(10));
        verify(jdbc).update(contains("UPDATE commerce_artwork_contracts SET artist_user_id"),
            eq(20),eq(10));
        verify(jdbc).update(contains("UPDATE commerce_royalty_accruals SET artist_user_id"),
            eq(20),eq(10));
        verify(jdbc).update(contains("UPDATE commerce_settlements SET artist_user_id"),
            eq(20),eq(10));
        verify(ownership).assertOnlyArtistCommerceReferences(10);
        verify(jdbc,never()).update(contains("signed_artist_user_id="),
            any(Object[].class));
    }

    @Test
    void unauthenticatedOrUnverifiedExistingUserCannotMerge() {
        invite(10,hash(CODE));
        User actor=user(20,"unconfirmed@example.invalid");
        when(authorization.currentUser(auth)).thenReturn(actor);
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(user(10,null)));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(actor));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.redeem(auth,new RedeemInvitation(TOKEN,CODE,"password")))
            .getStatusCode());
        verify(ownership,never()).assertOnlyArtistCommerceReferences(anyInt());
        verify(jdbc,never()).update(contains("UPDATE commerce_artworks"),
            any(Object[].class));
    }
}
