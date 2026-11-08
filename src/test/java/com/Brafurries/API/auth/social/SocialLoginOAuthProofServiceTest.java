package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialLoginProof;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialLoginProofRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialLoginOAuthProofServiceTest {
    @Mock UserRepository users;
    @Mock UserSocialLoginProofRepository proofs;
    @Mock TokenService tokens;
    SocialLoginOAuthProofService service;
    User owner;
    final String ticket = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";

    @BeforeEach void setup() {
        service = new SocialLoginOAuthProofService(users, proofs, tokens);
        owner = new User();
        owner.setId(5);
        owner.setEmail("local@example.com");
    }

    private String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(ticket.getBytes(StandardCharsets.UTF_8)));
    }

    private UserSocialLoginProof challenge(String state) {
        var proof = new UserSocialLoginProof();
        proof.setRequester(owner);
        proof.setProvider("google");
        proof.setStatus(state);
        proof.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        if ("VERIFIED".equals(state)) proof.setProviderSubject("google-sub-5");
        return proof;
    }

    @Test void startingProofUsesAuthenticatedBraFurriesOwnerAndStoresOnlyTicketHash() throws Exception {
        when(users.findByEmail("local@example.com")).thenReturn(Optional.of(owner));
        when(tokens.generateToken(32)).thenReturn(ticket);
        var auth = new UsernamePasswordAuthenticationToken("local@example.com", null, List.of());
        assertEquals(ticket, service.beginGoogle(auth).ticket());
        verify(proofs).save(org.mockito.ArgumentMatchers.argThat(proof ->
            proof.getRequester() == owner && "google".equals(proof.getProvider())
            && "STARTED".equals(proof.getStatus()) && !ticket.equals(proof.getTicketHash())
            && proof.getTicketHash().equals(hexDigest(ticket))));
    }

    private String hexDigest(String candidate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(candidate.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Test void verifiedCallbackStoresStableSubjectWithoutCreatingSiteLogin() throws Exception {
        var proof = challenge("STARTED");
        when(proofs.findByTicketHashForUpdate(hash())).thenReturn(Optional.of(proof));
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(),
            Map.of("sub", "google-sub-5", "email", "not-for-site@example.com"), "sub");
        OAuth2AuthenticationToken google = new OAuth2AuthenticationToken(principal, List.of(), "google");
        service.verifiedGoogleOAuth(ticket, google);
        assertEquals("VERIFIED", proof.getStatus());
        assertEquals("google-sub-5", proof.getProviderSubject());
        verify(proofs).save(proof);
        verifyNoInteractions(users, tokens);
    }

    @Test void ownerConsumesVerifiedTicketAtMostOnce() throws Exception {
        var proof = challenge("VERIFIED");
        when(proofs.findByTicketHashForUpdate(hash())).thenReturn(Optional.of(proof));
        assertEquals("google-sub-5", service.consumeVerifiedGoogle(owner, ticket));
        assertEquals("CONSUMED", proof.getStatus());
        assertNotNull(proof.getConsumedAt());
        assertThrows(ResponseStatusException.class,
            () -> service.consumeVerifiedGoogle(owner, ticket));
    }

    @Test void crossAccountTicketCannotReenableAnotherUsersGoogle() throws Exception {
        var proof = challenge("VERIFIED");
        when(proofs.findByTicketHashForUpdate(hash())).thenReturn(Optional.of(proof));
        User other = new User();
        other.setId(7);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.consumeVerifiedGoogle(other, ticket));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("VERIFIED", proof.getStatus());
        verify(proofs, never()).save(any());
    }

    @Test void expiryIsRejectedEvenWithVerifiedProviderSubject() throws Exception {
        var proof = challenge("VERIFIED");
        proof.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(proofs.findByTicketHashForUpdate(hash())).thenReturn(Optional.of(proof));
        assertThrows(ResponseStatusException.class, () -> service.consumeVerifiedGoogle(owner, ticket));
    }
}
