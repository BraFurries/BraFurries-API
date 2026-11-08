package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.UserSocialOnboardingChallenge;
import com.Brafurries.API.repository.user.UserSocialOnboardingChallengeRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialOnboardingChallengeServiceTest {
    @Mock UserSocialOnboardingChallengeRepository challenges;
    @Mock TokenService tokens;

    private SocialOnboardingChallengeService service() {
        return new SocialOnboardingChallengeService(challenges, tokens);
    }

    @Test
    void storesOnlyHashOfOpaqueTokenAndOptionalEditableEmailSuggestion() {
        when(tokens.generateToken(48)).thenReturn("opaque-proof-secret");
        var created = service().create("google", "stable-google-sub",
            "suggestion@example.com", "Luna");

        ArgumentCaptor<UserSocialOnboardingChallenge> saved =
            ArgumentCaptor.forClass(UserSocialOnboardingChallenge.class);
        verify(challenges).saveAndFlush(saved.capture());
        var row = saved.getValue();
        assertEquals("opaque-proof-secret", created.token());
        assertEquals("google", row.getProvider());
        assertEquals("stable-google-sub", row.getProviderSubject());
        assertEquals("suggestion@example.com", row.getSuggestedEmail());
        assertNotEquals(created.token(), row.getTokenHash());
        assertEquals(64, row.getTokenHash().length());
        assertTrue(row.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void requiresSingleUseProofAndRejectsReplayAfterConsume() {
        UserSocialOnboardingChallenge row = new UserSocialOnboardingChallenge();
        row.setProvider("discord");
        row.setProviderSubject("12345678");
        row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(challenges.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(row));
        when(challenges.findByTokenHash(anyString())).thenReturn(Optional.of(row));

        var locked = service().lockVerified("proof");
        service().consume(locked);
        assertNotNull(row.getConsumedAt());
        assertThrows(ResponseStatusException.class, () -> service().lockVerified("proof"));
        assertThrows(ResponseStatusException.class, () -> service().preview("proof"));
        verify(challenges).saveAndFlush(row);
    }

    @Test
    void allowsDiscordIdentifyOnlyWithoutProviderEmailAndNeverSuggestsOne() {
        when(tokens.generateToken(48)).thenReturn("discord-proof");
        service().create("discord", "987654321", null, "Dragon");
        ArgumentCaptor<UserSocialOnboardingChallenge> saved =
            ArgumentCaptor.forClass(UserSocialOnboardingChallenge.class);
        verify(challenges).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getSuggestedEmail());
    }

    @Test
    void rejectsExpiredChallengeEvenIfStillPersisted() {
        UserSocialOnboardingChallenge row = new UserSocialOnboardingChallenge();
        row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(challenges.findByTokenHash(anyString())).thenReturn(Optional.of(row));
        assertEquals("SOCIAL_REGISTRATION_EXPIRED",
            assertThrows(ResponseStatusException.class, () -> service().preview("proof")).getReason());
    }
}
