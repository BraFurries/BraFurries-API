package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecruitmentFeatureGateTest {
    private static final int OFFICIAL_COMMUNITY = 7;
    private static final int OTHER_COMMUNITY = 8;

    @Mock CommunityDiscordRepository communities;

    @Test
    void onlyActiveOfficialGuildCommunityMayAccessRecruitment() {
        when(communities.existsByGuildIdAndCommunityIdAndActiveTrue(
            RecruitmentFeatureGate.BRAFURRIES_GUILD_ID, OFFICIAL_COMMUNITY
        )).thenReturn(true);
        RecruitmentFeatureGate gate = new RecruitmentFeatureGate(communities, true, 0);

        assertDoesNotThrow(() -> gate.requireEnabledFor(OFFICIAL_COMMUNITY));
        verify(communities).existsByGuildIdAndCommunityIdAndActiveTrue(
            RecruitmentFeatureGate.BRAFURRIES_GUILD_ID, OFFICIAL_COMMUNITY);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> gate.requireEnabledFor(OTHER_COMMUNITY)).getStatusCode());
    }

    @Test
    void inactiveOrAbsentOfficialGuildFailsClosed() {
        RecruitmentFeatureGate gate = new RecruitmentFeatureGate(communities, true, 0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> gate.requireEnabledFor(OFFICIAL_COMMUNITY)).getStatusCode());
    }

    @Test
    void explicitEnvironmentDisableOverridesValidOfficialCommunity() {
        RecruitmentFeatureGate gate = new RecruitmentFeatureGate(communities, false, 0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> gate.requireEnabledFor(OFFICIAL_COMMUNITY)).getStatusCode());
        verifyNoInteractions(communities);
    }

    @Test
    void explicitlyConfiguredCommunityMustAlsoMatchOfficialGuild() {
        RecruitmentFeatureGate gate = new RecruitmentFeatureGate(communities, true, OFFICIAL_COMMUNITY);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> gate.requireEnabledFor(OTHER_COMMUNITY)).getStatusCode());
        verifyNoInteractions(communities);
        // Even an explicitly configured ID does not bypass the official-guild verification.
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> gate.requireEnabledFor(OFFICIAL_COMMUNITY)).getStatusCode());
    }

    @Test
    void missingInvalidOrNullTenantIdsFailBeforeRepositoryQuery() {
        RecruitmentFeatureGate gate = new RecruitmentFeatureGate(communities, true, 0);
        for (Integer id : new Integer[] {null, 0, -1}) {
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> gate.requireEnabledFor(id)).getStatusCode());
        }
        verifyNoInteractions(communities);
    }
}
