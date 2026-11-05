package com.Brafurries.API.auth.social;

import com.Brafurries.API.admin.IdentityBanPropagationService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.*;
import com.Brafurries.API.repository.user.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscordSelfServiceLinkServiceTest {
    private static final String TICKET = "Abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";

    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserIdentityLinkRepository links;
    @Mock UserDiscordLinkChallengeRepository challenges;
    @Mock IdentityBanPropagationService bans;
    @Mock TokenService tokens;

    private DiscordSelfServiceLinkService service() {
        return new DiscordSelfServiceLinkService(users, discord, memberships, links, challenges, bans, tokens);
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken("person@example.com", null, List.of());
    }

    private User user(int id) {
        User u = new User();
        u.setId(id);
        u.setEmail(id == 10 ? "person@example.com" : null);
        return u;
    }

    private UserDiscordLinkChallenge verified(User user) {
        UserDiscordLinkChallenge proof = new UserDiscordLinkChallenge();
        proof.setRequester(user);
        proof.setStatus("VERIFIED");
        proof.setDiscordUserId(123456789L);
        proof.setDiscordUsername("Furry");
        proof.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return proof;
    }

    @Test
    void startRequiresWebsiteAuthenticationAndNeverStoresRawTicket() {
        User owner = user(10);
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(owner));
        when(tokens.generateToken(32)).thenReturn(TICKET);

        var result = service().start(auth());

        assertEquals(TICKET, result.ticket());
        ArgumentCaptor<UserDiscordLinkChallenge> captor = ArgumentCaptor.forClass(UserDiscordLinkChallenge.class);
        verify(challenges).save(captor.capture());
        assertNotEquals(TICKET, captor.getValue().getTicketHash());
        assertEquals(64, captor.getValue().getTicketHash().length());
        assertEquals("STARTED", captor.getValue().getStatus());
        assertEquals(owner, captor.getValue().getRequester());

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
            () -> service().start(null)).getStatusCode());
    }

    @Test
    void oauthProofRequiresDiscordProviderNotOnlyEmailOrUserSuppliedId() {
        var google = mock(OAuth2AuthenticationToken.class);
        when(google.getAuthorizedClientRegistrationId()).thenReturn("google");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service().verifiedDiscordOAuth(TICKET, google)).getStatusCode());
        verifyNoInteractions(challenges);
    }

    @Test
    void oauthVerifiedTicketIsOneTimeAndCannotBeReplayedOrReadByAnotherUser() {
        User owner = user(10);
        UserDiscordLinkChallenge challenge = verified(owner);
        challenge.setStatus("STARTED");
        when(challenges.findByTicketHashForUpdate(anyString())).thenReturn(Optional.of(challenge));
        var principal = new DefaultOAuth2User(List.of(), java.util.Map.of(
            "id", "123456789", "username", "Furry"), "id");
        var oauth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "discord");
        service().verifiedDiscordOAuth(TICKET, oauth);
        assertEquals("VERIFIED", challenge.getStatus());
        assertEquals(123456789L, challenge.getDiscordUserId());
        assertThrows(ResponseStatusException.class,
            () -> service().verifiedDiscordOAuth(TICKET, oauth));
    }

    @Test
    void expiredProofCannotStartOrBeConfirmed() {
        UserDiscordLinkChallenge proof = verified(user(10));
        proof.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(challenges.findByTicketHash(anyString())).thenReturn(Optional.of(proof));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> service().authorizeOAuthStart(TICKET)).getStatusCode());
    }

    @Test
    void previewNeedsCurrentGoogleOwnerEvenAfterDiscordOauth() {
        User other = user(12);
        UserDiscordLinkChallenge proof = verified(other);
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(user(10)));
        when(challenges.findByTicketHash(anyString())).thenReturn(Optional.of(proof));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> service().preview(auth(), TICKET)).getStatusCode());
        verifyNoInteractions(discord);
    }

    @Test
    void legacyRecoveryOnlyCreatesAuditedIdentityEdgeAndNeverMovesDiscordRow() {
        User web = user(10);
        User legacy = user(20);
        UserDiscordLinkChallenge proof = verified(web);
        UserDiscord legacyDiscord = new UserDiscord();
        legacyDiscord.setUser(legacy);
        legacyDiscord.setDiscordUserId(123456789L);
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(web));
        when(challenges.findByTicketHashForUpdate(anyString())).thenReturn(Optional.of(proof));
        when(discord.findByDiscordUserId(123456789L)).thenReturn(Optional.of(legacyDiscord));
        when(discord.findByUser(web)).thenReturn(Optional.empty());
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(web));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(legacy));
        when(links.findActiveLinksContainingUser(20)).thenReturn(List.of());
        when(links.findActiveByPair(10,20)).thenReturn(Optional.empty());

        var result = service().confirm(auth(), TICKET);

        assertEquals("HISTORY_CONNECTED", result.status());
        ArgumentCaptor<UserIdentityLink> edge = ArgumentCaptor.forClass(UserIdentityLink.class);
        verify(links).saveAndFlush(edge.capture());
        assertEquals(UserIdentityLinkStatus.CONFIRMED, edge.getValue().getStatus());
        assertEquals(UserIdentityLinkSource.SOCIAL, edge.getValue().getLinkSource());
        assertEquals(10, edge.getValue().getUserA().getId());
        assertEquals(20, edge.getValue().getUserB().getId());
        verify(discord, never()).saveAndFlush(any());
        verify(users, never()).delete(any());
        assertEquals(legacy, legacyDiscord.getUser());
        assertEquals("CONSUMED", proof.getStatus());
    }

    @Test
    void newDiscordCreatesLinkOnlyForProvenRequester() {
        User web = user(10);
        UserDiscordLinkChallenge proof = verified(web);
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(web));
        when(challenges.findByTicketHashForUpdate(anyString())).thenReturn(Optional.of(proof));

        assertEquals("CONNECTED", service().confirm(auth(), TICKET).status());
        ArgumentCaptor<UserDiscord> created = ArgumentCaptor.forClass(UserDiscord.class);
        verify(discord).saveAndFlush(created.capture());
        assertEquals(10, created.getValue().getUser().getId());
        assertEquals(123456789L, created.getValue().getDiscordUserId());
        verifyNoInteractions(links);
    }

    @Test
    void existingOtherDiscordCannotBeReassignedOrUsedToMergeUsers() {
        User web = user(10);
        UserDiscordLinkChallenge proof = verified(web);
        UserDiscord own = new UserDiscord();
        own.setUser(web);
        own.setDiscordUserId(999L);
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(web));
        when(challenges.findByTicketHashForUpdate(anyString())).thenReturn(Optional.of(proof));
        when(discord.findByUser(web)).thenReturn(Optional.of(own));

        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class, () -> service().confirm(auth(), TICKET)).getStatusCode());
        verifyNoInteractions(links);
        verify(discord, never()).saveAndFlush(any());
        assertEquals("VERIFIED", proof.getStatus());
    }

    @Test
    void profileFallbackIgnoresAdminOnlyLinksAndExistingWebHistory() {
        User web = user(10), legacy = user(20);
        when(memberships.countByUserId(10)).thenReturn(0L);
        when(links.findActiveLinksContainingUser(10)).thenReturn(List.of(link(web, legacy, UserIdentityLinkSource.ADMIN)));
        assertEquals(web, service().legacyProfileFor(web));

        reset(links);
        when(memberships.countByUserId(10)).thenReturn(1L);
        assertEquals(web, service().legacyProfileFor(web));
        verifyNoInteractions(links);
    }

    @Test
    void provenSocialLinkRestoresLegacyDataButDoesNotAuthenticateAnAdminLink() {
        User web = user(10), legacy = user(20);
        UserDiscord original = new UserDiscord();
        original.setUser(legacy);
        when(memberships.countByUserId(10)).thenReturn(0L);
        when(links.findActiveLinksContainingUser(10)).thenReturn(List.of(link(web, legacy, UserIdentityLinkSource.SOCIAL)));
        when(discord.findByUser(legacy)).thenReturn(Optional.of(original));
        assertEquals(legacy, service().legacyProfileFor(web));
        when(links.findActiveLinksContainingUser(20)).thenReturn(List.of(link(web, legacy, UserIdentityLinkSource.SOCIAL)));
        assertEquals(web, service().recoveredLoginFor(legacy).orElseThrow());
        when(links.findActiveByPair(10,20)).thenReturn(Optional.of(link(web, legacy, UserIdentityLinkSource.SOCIAL)));
        assertTrue(service().isVerifiedLink(web, legacy));
    }

    private UserIdentityLink link(User a, User b, UserIdentityLinkSource source) {
        UserIdentityLink edge = new UserIdentityLink();
        edge.setUserA(a);
        edge.setUserB(b);
        edge.setStatus(UserIdentityLinkStatus.CONFIRMED);
        edge.setLinkSource(source);
        return edge;
    }
}
