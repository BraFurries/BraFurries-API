package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GuildManagementServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock GuildConfigurationStore store;
    @Mock CoddyGuildManagementClient coddy;
    @Mock Authentication authentication;
    @InjectMocks GuildManagementService service;
    private GuildResources resources;

    @BeforeEach void setUp() {
        resources = new GuildResources("123456789012345678",
            List.of(new GuildRole("900000000000000001", "Visitante antigo", 10, false, true),
                new GuildRole("900000000000000002", "Visitante novo", 9, false, true)),
            List.of(new GuildChannel("800000000000000001", "fichas", "text", "700000000000000001")),
            List.of(new GuildCategory("700000000000000001", "Portaria")),
            new BotCapabilities(true, true, true, List.of()));
        lenient().when(access.authorize(authentication, resources.guildId())).thenReturn(new AuthorizedGuild(42L, resources));
    }

    @Test void readsAndWritesSharedStaffRoles() {
        when(store.readStaffRoleIds(Long.parseLong(resources.guildId()))).thenReturn(List.of("900000000000000001"));
        StaffRolesResponse read = service.staffRoles(authentication, resources.guildId());
        assertEquals("900000000000000001", read.roles().getFirst().id());
        StaffRolesResponse written = service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of("900000000000000001")));
        assertEquals(List.of("900000000000000001"), written.roleIds());
        verify(store).writeStaffRoleIds(Long.parseLong(resources.guildId()), List.of("900000000000000001"));
    }

    @Test void readsAggregatedBumpConfigurationWithoutNormalizingLegacyCoinFallback() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig("primeira||segunda", true, 25));

        BumpConfigResponse response = service.bump(authentication, resources.guildId());

        assertEquals(List.of("primeira", "segunda"), response.warning().messages());
        assertTrue(response.individualReward().coins().enabled());
        assertEquals(25, response.individualReward().coins().amount());
        verify(store).readBumpConfig(guildId);
        verify(store, never()).writeBumpCoinReward(anyLong(), any());
    }

    @Test void exposesCanonicalProcessingChannelBeforeLegacyWarningAliasWithoutWritingOnGet() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig("800000000000000010", "800000000000000001", null, false, null));

        BumpConfigResponse response = service.bump(authentication, resources.guildId());

        assertEquals("800000000000000010", response.processing().channelId());
        assertEquals("800000000000000010", response.warning().sourceChannelId());
        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
        verify(store, never()).writeBumpWarning(anyLong(), anyBoolean(), any(), any());
    }

    @Test void exposesLegacyWarningSourceOnlyWhenCanonicalProcessingIsAbsent() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, "800000000000000001", null, false, null));

        BumpConfigResponse response = service.bump(authentication, resources.guildId());

        assertEquals("800000000000000001", response.processing().channelId());
        assertEquals("800000000000000001", response.warning().sourceChannelId());
        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
    }

    @Test void exposesNoProcessingChannelWhenNeitherCanonicalNorLegacyChannelExists() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, null, null, false, null));

        BumpConfigResponse response = service.bump(authentication, resources.guildId());

        assertNull(response.processing().channelId());
        assertNull(response.warning().sourceChannelId());
    }

    @Test void canonicalProcessingWriteReturnsCanonicalChannelAfterLegacyFallbackIsRetired() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig("800000000000000001", null, null, false, null));

        BumpConfigResponse response = service.updateBumpProcessing(authentication, resources.guildId(),
            new BumpProcessingChannelRequest("800000000000000001"));

        assertEquals("800000000000000001", response.processing().channelId());
        assertEquals("800000000000000001", response.warning().sourceChannelId());
        verify(store).writeBumpProcessingChannel(guildId, "800000000000000001");
    }

    @Test void clearingCanonicalProcessingReturnsNullAfterLegacyFallbackIsRetired() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, null, null, false, null));

        BumpConfigResponse response = service.updateBumpProcessing(authentication, resources.guildId(),
            new BumpProcessingChannelRequest(null));

        assertNull(response.processing().channelId());
        assertNull(response.warning().sourceChannelId());
        verify(store).writeBumpProcessingChannel(guildId, null);
    }

    @Test void warningWithoutDeprecatedSourceKeepsProcessingUnchanged() throws Exception {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, null, null, false, null));

        service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(true, null, "800000000000000001", List.of(" aviso ")));

        verify(store).writeBumpWarning(guildId, true, "800000000000000001", "[\"aviso\"]");
        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
        assertTrue(GuildManagementService.class.getMethod("updateBumpWarning", Authentication.class,
            String.class, BumpWarningRequest.class).isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test void deprecatedWarningSourceUpdatesCanonicalProcessingAndNeverLegacySettings() {
        long guildId = Long.parseLong(resources.guildId());
        GuildResources channels = resourcesWithChannels(
            new GuildChannel("800000000000000001", "origem", "text", null),
            new GuildChannel("800000000000000002", "destino", "news", null));
        when(access.authorize(authentication, channels.guildId())).thenReturn(new AuthorizedGuild(42L, channels));
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, null, null, false, null));

        service.updateBumpWarning(authentication, channels.guildId(),
            new BumpWarningRequest(true, "800000000000000001", "800000000000000002", List.of()));

        verify(store).writeBumpProcessingChannel(guildId, "800000000000000001");
        verify(store).writeBumpWarning(guildId, true, "800000000000000002", null);
    }

    @Test void invalidDeprecatedWarningSourceRejectsBeforeAnyWrite() {
        long guildId = Long.parseLong(resources.guildId());

        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(true, "invalid", "800000000000000001", List.of())));

        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
        verify(store, never()).writeBumpWarning(anyLong(), anyBoolean(), any(), any());
    }

    @Test void rejectsEffectiveCoinsWithoutEconomyBeforeWritingRoleReward() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.hasEconomyConfig(guildId)).thenReturn(false);
        BumpIndividualRewardRequest request = new BumpIndividualRewardRequest(
            new BumpRoleRewardRequest(false, null, 0, null), new BumpCoinRewardRequest(true, 25));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.updateBumpIndividualReward(authentication, resources.guildId(), request));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(store, never()).writeBumpRoleReward(anyLong(), any());
        verify(store, never()).writeBumpCoinReward(anyLong(), any());
    }

    @Test void oldAndNewProcessingEndpointsShareNullMeansAllChannelsPersistence() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readServerChannels(guildId)).thenReturn(new ServerChannelsConfig(null, List.of(), false, null));
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, false, null));

        service.updateBumpProcessingChannel(authentication, resources.guildId(), new BumpProcessingChannelRequest(null));
        service.updateBumpProcessing(authentication, resources.guildId(), new BumpProcessingChannelRequest(null));

        verify(store, times(2)).writeBumpProcessingChannel(guildId, null);
    }

    @Test void processingAcceptsTextAndNewsAndRejectsForeignOrNonTextChannels() {
        long guildId = Long.parseLong(resources.guildId());
        GuildResources mixed = resourcesWithChannels(
            new GuildChannel("800000000000000010", "texto", "text", null),
            new GuildChannel("800000000000000011", "notícias", "news", null),
            new GuildChannel("800000000000000012", "voz", "voice", null),
            new GuildChannel("800000000000000013", "fórum", "forum", null),
            new GuildChannel("800000000000000014", "palco", "stage_voice", null));
        when(access.authorize(authentication, mixed.guildId())).thenReturn(new AuthorizedGuild(42L, mixed));
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, false, null));

        service.updateBumpProcessing(authentication, mixed.guildId(), new BumpProcessingChannelRequest("800000000000000010"));
        service.updateBumpProcessing(authentication, mixed.guildId(), new BumpProcessingChannelRequest("800000000000000011"));
        for (String invalid : List.of("800000000000000012", "800000000000000013", "800000000000000014", "999", "bad"))
            assertThrows(ResponseStatusException.class,
                () -> service.updateBumpProcessing(authentication, mixed.guildId(), new BumpProcessingChannelRequest(invalid)));
        verify(store).writeBumpProcessingChannel(guildId, "800000000000000010");
        verify(store).writeBumpProcessingChannel(guildId, "800000000000000011");
    }

    @Test void warningValidatesRequiredChannelsAndMessageBoundsWithoutCrossSectionWrites() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig("[\"primeira\",\"segunda\"]", false, null));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(true, null, null, List.of())));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(true, "bad", "800000000000000001", List.of())));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(true, "800000000000000001", "bad", List.of())));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(false, null, null, List.of(" "))));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(false, null, null, List.of("x".repeat(1501)))));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpWarning(authentication, resources.guildId(),
            new BumpWarningRequest(false, null, null, java.util.Collections.nCopies(21, "ok"))));
        service.updateBumpWarning(authentication, resources.guildId(), new BumpWarningRequest(false, null, null, List.of()));
        verify(store).writeBumpWarning(guildId, false, null, null);
        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
        verify(store, never()).writeBumpMonthlyRanking(anyLong(), any(), any());
        verify(store, never()).writeBumpRoleReward(anyLong(), any());
        verify(store, never()).writeBumpCoinReward(anyLong(), any());
        assertEquals(List.of("primeira", "segunda"), service.bump(authentication, resources.guildId()).warning().messages());
    }

    @Test void individualRewardValidatesRoleAndCoinContractsAndStopsAfterRoleWriteFailure() throws Exception {
        long guildId = Long.parseLong(resources.guildId());
        BumpIndividualRewardRequest missingRole = new BumpIndividualRewardRequest(
            new BumpRoleRewardRequest(true, null, 1, null), new BumpCoinRewardRequest(false, 0));
        BumpIndividualRewardRequest missingCoins = new BumpIndividualRewardRequest(
            new BumpRoleRewardRequest(false, null, 0, null), new BumpCoinRewardRequest(true, 0));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpIndividualReward(authentication, resources.guildId(), missingRole));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpIndividualReward(authentication, resources.guildId(), missingCoins));
        assertThrows(ResponseStatusException.class, () -> service.updateBumpIndividualReward(authentication, resources.guildId(),
            new BumpIndividualRewardRequest(new BumpRoleRewardRequest(false, "bad", 0, null), new BumpCoinRewardRequest(false, -1))));
        doThrow(new IllegalStateException("write failed")).when(store).writeBumpRoleReward(eq(guildId), any());
        assertThrows(IllegalStateException.class, () -> service.updateBumpIndividualReward(authentication, resources.guildId(),
            new BumpIndividualRewardRequest(new BumpRoleRewardRequest(false, null, 0, null), new BumpCoinRewardRequest(false, 0))));
        verify(store, never()).writeBumpCoinReward(anyLong(), any());
        assertTrue(GuildManagementService.class.getMethod("updateBumpIndividualReward", Authentication.class,
            String.class, BumpIndividualRewardRequest.class).isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test void monthlyCanonicalizesTiersAndRejectsInvalidConfigurationsWithoutOtherWrites() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readBumpConfig(guildId)).thenReturn(bumpConfig(null, false, null));
        BumpMonthlyRankingRequest valid = new BumpMonthlyRankingRequest(true, "800000000000000001", "900000000000000001",
            List.of(new BumpMonthlyRewardTier(3, 7, 0), new BumpMonthlyRewardTier(1, 21, 10), new BumpMonthlyRewardTier(2, 14, 0)));
        service.updateBumpMonthlyRanking(authentication, resources.guildId(), valid);
        var tiers = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).writeBumpMonthlyRanking(eq(guildId), eq(valid), tiers.capture());
        assertEquals(List.of(1, 2, 3), ((List<BumpMonthlyRewardTier>) tiers.getValue()).stream().map(BumpMonthlyRewardTier::position).toList());
        for (BumpMonthlyRankingRequest invalid : List.of(
            new BumpMonthlyRankingRequest(true, null, null, List.of(new BumpMonthlyRewardTier(1, 0, 1), new BumpMonthlyRewardTier(2, 0, 0), new BumpMonthlyRewardTier(3, 0, 0))),
            new BumpMonthlyRankingRequest(false, null, null, List.of(new BumpMonthlyRewardTier(1, 1, 0), new BumpMonthlyRewardTier(2, 0, 0), new BumpMonthlyRewardTier(3, 0, 0))),
            new BumpMonthlyRankingRequest(false, null, null, List.of(new BumpMonthlyRewardTier(1, 0, -1), new BumpMonthlyRewardTier(2, 0, 0), new BumpMonthlyRewardTier(3, 0, 0))),
            new BumpMonthlyRankingRequest(false, null, null, List.of(new BumpMonthlyRewardTier(1, 0, 0), new BumpMonthlyRewardTier(1, 0, 0), new BumpMonthlyRewardTier(3, 0, 0)))))
            assertThrows(ResponseStatusException.class, () -> service.updateBumpMonthlyRanking(authentication, resources.guildId(), invalid));
        verify(store, never()).writeBumpWarning(anyLong(), anyBoolean(), any(), any());
        verify(store, never()).writeBumpProcessingChannel(anyLong(), any());
        verify(store, never()).writeBumpRoleReward(anyLong(), any());
    }

    @Test void savesOnlyValidatedTextChannelsAndKeepsAiLimitExplicit() {
        long guildId = Long.parseLong(resources.guildId());
        ServerChannelsConfig state = new ServerChannelsConfig("800000000000000001", List.of("800000000000000001"), true, "800000000000000001");
        when(store.readServerChannels(guildId)).thenReturn(state);

        assertEquals(state, service.updateBirthdayChannel(authentication, resources.guildId(), new BirthdayChannelRequest("800000000000000001")));
        assertEquals(state, service.updateAiChannels(authentication, resources.guildId(), new AiChannelsRequest(true, List.of("800000000000000001", "800000000000000001"))));
        verify(store).writeBirthdayChannel(guildId, "800000000000000001");
        verify(store).writeAiChannels(guildId, true, List.of("800000000000000001"));

        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
            () -> service.updateAiChannels(authentication, resources.guildId(), new AiChannelsRequest(true, List.of("999"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatusCode());
        ResponseStatusException malformed = assertThrows(ResponseStatusException.class,
            () -> service.updateBirthdayChannel(authentication, resources.guildId(), new BirthdayChannelRequest("not-a-snowflake")));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        ResponseStatusException emptyLimited = assertThrows(ResponseStatusException.class,
            () -> service.updateAiChannels(authentication, resources.guildId(), new AiChannelsRequest(true, List.of())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, emptyLimited.getStatusCode());
    }

    @Test void togglesPortariaLifecycleIndependentlyFromFormConfiguration() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaResponse expected = response(List.of(), null, false, false);
        when(store.readPortaria(guildId, null)).thenReturn(expected);

        PortariaResponse result = service.updatePortariaEnabled(
            authentication,
            resources.guildId(),
            new PortariaEnabledRequest(false)
        );

        assertSame(expected, result);
        verify(store).writePortariaEnabled(guildId, false);
    }

    @Test void publishesAValidatedPortariaFlowAndMapsCreatedPublication() {
        long guildId = Long.parseLong(resources.guildId());
        var coddyResult = publicationResult("created");
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(coddyResult);

        PortariaPublicationResult result = service.publishPortariaFlow(authentication, resources.guildId(), 7,
            new PortariaPublicationRequest("800000000000000001", null, null));

        assertEquals(new PortariaPublicationResult(7, "800000000000000001", "810000000000000001", true, false, false), result);
        verify(store).requirePortariaFlow(guildId, 7);
        var capture = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(coddy).operation(eq(resources.guildId()), eq(42L), capture.capture());
        Map<?, ?> operation = (Map<?, ?>) capture.getValue();
        assertEquals("portaria-publish", operation.get("operation"));
        assertEquals(7, operation.get("flowId"));
        assertEquals("800000000000000001", operation.get("welcomeChannelId"));
        assertFalse(operation.containsKey("message"));
        assertFalse(operation.containsKey("buttonText"));
    }

    @Test void mapsReusedPublicationWithoutExposingCoddyPayload() {
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(publicationResult("reused"));

        PortariaPublicationResult result = service.publishPortariaFlow(authentication, resources.guildId(), 7,
            new PortariaPublicationRequest("800000000000000001", null, null));

        assertEquals("810000000000000001", result.messageId());
        assertFalse(result.created());
        assertTrue(result.reused());
        assertFalse(result.updated());
    }

    @Test void forwardsCustomPublicationFieldsIncludingAnEmptyMessage() {
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(publicationResult("updated"));
        PortariaPublicationResult result = service.publishPortariaFlow(authentication, resources.guildId(), 7,
            new PortariaPublicationRequest("800000000000000001", "", "Fazer minha ficha"));
        var capture = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(coddy).operation(eq(resources.guildId()), eq(42L), capture.capture());
        Map<?, ?> payload = (Map<?, ?>) capture.getValue();
        assertEquals("", payload.get("message"));
        assertEquals("Fazer minha ficha", payload.get("buttonText"));
        assertFalse(result.created()); assertFalse(result.reused()); assertTrue(result.updated());
    }

    @Test void rejectsInvalidPublicationCustomizationBeforeCallingCoddy() {
        for (PortariaPublicationRequest request : List.of(
            new PortariaPublicationRequest("800000000000000001", "x".repeat(2001), null),
            new PortariaPublicationRequest("800000000000000001", null, ""),
            new PortariaPublicationRequest("800000000000000001", null, "   "),
            new PortariaPublicationRequest("800000000000000001", null, "x".repeat(81))
        )) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.publishPortariaFlow(authentication, resources.guildId(), 7, request));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }

        verifyNoInteractions(coddy);
    }

    @Test void mapsEmptyCoddyPublicationArraysAsNotCreatedOrReused() {
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(publicationResult(null));

        PortariaPublicationResult result = service.publishPortariaFlow(authentication, resources.guildId(), 7,
            new PortariaPublicationRequest("800000000000000001", null, null));

        assertFalse(result.created());
        assertFalse(result.reused());
        assertFalse(result.updated());
    }

    @Test void rejectsUnauthorizedPublicationBeforeFlowOrCoddyAccess() {
        when(access.authorize(authentication, resources.guildId())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.publishPortariaFlow(authentication, resources.guildId(), 7,
                new PortariaPublicationRequest("800000000000000001", null, null)));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(store, coddy);
    }

    @Test void rejectsMissingOrForeignFlowBeforeCallingCoddy() {
        long guildId = Long.parseLong(resources.guildId());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Fluxo da Portaria não encontrado neste servidor"))
            .when(store).requirePortariaFlow(guildId, 7);

        assertThrows(ResponseStatusException.class, () -> service.publishPortariaFlow(authentication, resources.guildId(), 7,
            new PortariaPublicationRequest("800000000000000001", null, null)));

        verifyNoInteractions(coddy);
    }

    @Test void rejectsInvalidForeignAndNonTextPublicationChannelsBeforeCallingCoddy() {
        long guildId = Long.parseLong(resources.guildId());
        GuildResources mixed = resourcesWithChannels(
            new GuildChannel("800000000000000001", "entrada", "text", null),
            new GuildChannel("800000000000000002", "voz", "voice", null));
        when(access.authorize(authentication, mixed.guildId())).thenReturn(new AuthorizedGuild(42L, mixed));

        for (String channelId : List.of("not-a-snowflake", "999999999999999999", "800000000000000002")) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.publishPortariaFlow(authentication, mixed.guildId(), 7, new PortariaPublicationRequest(channelId)));
            assertTrue(error.getStatusCode().is4xxClientError());
        }

        verify(store, times(2)).requirePortariaFlow(guildId, 7);
        verifyNoInteractions(coddy);
    }

    @Test void rejectsAnIncompleteCoddyPublicationPayload() {
        var incomplete = new ObjectMapper().createObjectNode();
        incomplete.putObject("resources").put("welcomeChannelId", "800000000000000001");
        incomplete.putArray("created");
        incomplete.putArray("reused");
        incomplete.putArray("updated");
        incomplete.putArray("warnings");
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(incomplete);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.publishPortariaFlow(authentication, resources.guildId(), 7,
                new PortariaPublicationRequest("800000000000000001")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test void doesNotAllowTheGenericOperationEndpointToInjectAPortariaPublication() {
        GuildOperationRequest injected = new GuildOperationRequest("portaria-publish", null, null, null,
            null, null, null, null, "other-role", "800000000000000001", "other-channel", null,
            null, 999, List.of("other-channel"), List.of("other-channel"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.operation(authentication, resources.guildId(), injected));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(coddy);
    }

    @Test void updatesIndependentBumpProcessingChannelWithoutUsingLegacyFields() {
        long guildId = Long.parseLong(resources.guildId());
        ServerChannelsConfig state = new ServerChannelsConfig(null, List.of(), false, "800000000000000001");
        when(store.readServerChannels(guildId)).thenReturn(state);

        assertEquals(state, service.updateBumpProcessingChannel(authentication, resources.guildId(),
            new BumpProcessingChannelRequest("800000000000000001")));
        verify(store).writeBumpProcessingChannel(guildId, "800000000000000001");
    }

    @Test void clearsIndependentBumpProcessingChannel() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readServerChannels(guildId)).thenReturn(new ServerChannelsConfig(null, List.of(), false, null));
        service.updateBumpProcessingChannel(authentication, resources.guildId(), new BumpProcessingChannelRequest(null));
        verify(store).writeBumpProcessingChannel(guildId, null);
    }

    @Test void doesNotReadChannelsForAGuildTheUserCannotManage() {
        when(access.authorize(authentication, resources.guildId())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.serverChannels(authentication, resources.guildId())).getStatusCode());
        verifyNoInteractions(store);
    }

    @Test void allowsClearingAllStaffRoles() {
        StaffRolesResponse written = service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of()));
        assertEquals(List.of(), written.roleIds());
        verify(store).writeStaffRoleIds(Long.parseLong(resources.guildId()), List.of());
    }

    @Test void writesVipRolesOnlyAfterValidatingTheyBelongToTheGuild() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readVipRoleIds(guildId)).thenReturn(List.of("900000000000000001"));
        assertEquals("900000000000000001", service.vipRoles(authentication, resources.guildId()).roles().getFirst().id());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.updateVipRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of("999"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeVipRoleIds(anyLong(), anyList());
        service.updateVipRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of("900000000000000001")));
        verify(store).writeVipRoleIds(guildId, List.of("900000000000000001"));
    }

    @Test void validatesPortariaRolesBeforeUpdatingOnlyTheirColumns() {
        PortariaRolesRequest invalid = new PortariaRolesRequest(null, "999", null, null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.updatePortariaRoles(authentication, resources.guildId(), invalid)).getStatusCode());
        verify(store, never()).writePortariaRoleIds(anyLong(), any());
    }

    @Test void keepsTheVisitorAndExistingAutoJoinStateWithoutRedundantMutations() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaRolesRequest request = new PortariaRolesRequest(null, "900000000000000001", null, null);
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(
            new AutoJoinRolesConfig(true, List.of(900000000000000001L)));
        when(store.readPortaria(guildId, null)).thenReturn(response(List.of(), null, false, false));

        service.updatePortariaRoles(authentication, resources.guildId(), request);

        verify(coddy, never()).addAutoJoin(anyString(), anyLong(), anyString());
        verify(coddy, never()).updateAutoJoinEnabled(anyString(), anyLong(), anyBoolean());
        verify(coddy, never()).removeAutoJoin(anyString(), anyLong(), anyString());
        verify(store).writePortariaRoleIds(guildId, request);
    }

    @Test void switchesVisitorWithoutSilentlyRemovingThePreviousAutomaticRole() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaRolesRequest request = new PortariaRolesRequest(null, "900000000000000002", null, null);
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(
            new AutoJoinRolesConfig(true, List.of(900000000000000001L)));
        when(store.readPortaria(guildId, null)).thenReturn(response(List.of(), null, false, false));

        service.updatePortariaRoles(authentication, resources.guildId(), request);

        verify(coddy).addAutoJoin(resources.guildId(), 42L, "900000000000000002");
        verify(coddy, never()).removeAutoJoin(anyString(), anyLong(), anyString());
        verify(store).writePortariaRoleIds(guildId, request);
    }

    @Test void clearsVisitorWithoutSilentlyRemovingItsPreviousAutomaticRole() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaRolesRequest request = new PortariaRolesRequest(null, null, null, null);
        when(store.readPortaria(guildId, null)).thenReturn(response(List.of(), null, false, false));

        service.updatePortariaRoles(authentication, resources.guildId(), request);

        verifyNoInteractions(coddy);
        verify(store).writePortariaRoleIds(guildId, request);
    }

    @Test void rollsBackNewAutomaticRoleAndEnabledStateWhenApiPersistenceFails() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaRolesRequest request = new PortariaRolesRequest(null, "900000000000000002", null, null);
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(
            new AutoJoinRolesConfig(false, List.of(900000000000000001L)));
        doThrow(new IllegalStateException("database unavailable")).when(store).writePortariaRoleIds(guildId, request);

        assertThrows(IllegalStateException.class,
            () -> service.updatePortariaRoles(authentication, resources.guildId(), request));

        var order = inOrder(coddy, store);
        order.verify(coddy).addAutoJoin(resources.guildId(), 42L, "900000000000000002");
        order.verify(coddy).updateAutoJoinEnabled(resources.guildId(), 42L, true);
        order.verify(store).writePortariaRoleIds(guildId, request);
        order.verify(coddy).updateAutoJoinEnabled(resources.guildId(), 42L, false);
        order.verify(coddy).removeAutoJoin(resources.guildId(), 42L, "900000000000000002");
    }

    @Test void neverRemovesAnIndependentAutomaticRoleWhenApiPersistenceFails() {
        long guildId = Long.parseLong(resources.guildId());
        PortariaRolesRequest request = new PortariaRolesRequest(null, "900000000000000002", null, null);
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(
            new AutoJoinRolesConfig(false, List.of(900000000000000001L, 900000000000000002L)));
        doThrow(new IllegalStateException("database unavailable")).when(store).writePortariaRoleIds(guildId, request);

        assertThrows(IllegalStateException.class,
            () -> service.updatePortariaRoles(authentication, resources.guildId(), request));

        verify(coddy).updateAutoJoinEnabled(resources.guildId(), 42L, true);
        verify(coddy).updateAutoJoinEnabled(resources.guildId(), 42L, false);
        verify(coddy, never()).removeAutoJoin(anyString(), anyLong(), anyString());
    }

    @Test void rollsBackTheNewRoleWhenCoddyFailsBetweenAddAndEnable() {
        PortariaRolesRequest request = new PortariaRolesRequest(null, "900000000000000002", null, null);
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(
            new AutoJoinRolesConfig(false, List.of(900000000000000001L)));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy indisponível"))
            .when(coddy).updateAutoJoinEnabled(resources.guildId(), 42L, true);

        assertThrows(ResponseStatusException.class,
            () -> service.updatePortariaRoles(authentication, resources.guildId(), request));

        verify(coddy).addAutoJoin(resources.guildId(), 42L, "900000000000000002");
        verify(coddy).removeAutoJoin(resources.guildId(), 42L, "900000000000000002");
        verify(store, never()).writePortariaRoleIds(anyLong(), any());
    }

    @Test void rejectsNullOrMalformedStaffRolesBeforePersistence() {
        ResponseStatusException nullList = assertThrows(ResponseStatusException.class,
            () -> service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(null)));
        assertEquals(HttpStatus.BAD_REQUEST, nullList.getStatusCode());

        ResponseStatusException blankRole = assertThrows(ResponseStatusException.class,
            () -> service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of(" "))));
        assertEquals(HttpStatus.BAD_REQUEST, blankRole.getStatusCode());

        ResponseStatusException malformedRole = assertThrows(ResponseStatusException.class,
            () -> service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of("abc"))));
        assertEquals(HttpStatus.BAD_REQUEST, malformedRole.getStatusCode());

        verifyNoInteractions(store);
    }

    @Test void rejectsRoleAndChannelFromAnotherGuildBeforePersistence() {
        ResponseStatusException roleError = assertThrows(ResponseStatusException.class,
            () -> service.updateStaffRoles(authentication, resources.guildId(), new StaffRolesRequest(List.of("999"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, roleError.getStatusCode());
        ResponseStatusException channelError = assertThrows(ResponseStatusException.class,
            () -> service.createFlow(authentication, resources.guildId(), new CreateFlowRequest("Portaria", "999")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, channelError.getStatusCode());
        verifyNoInteractions(store);
    }

    @Test void acceptsTextAndNewsChannelsForPortariaDestinations() {
        GuildResources mixed = resourcesWithChannels(
            new GuildChannel("800000000000000010", "fichas", "text", null),
            new GuildChannel("800000000000000011", "aprovados", "news", null));
        when(access.authorize(authentication, mixed.guildId())).thenReturn(new AuthorizedGuild(42L, mixed));
        when(store.createPortariaFlow(Long.parseLong(mixed.guildId()), new CreateFlowRequest("Portaria", "800000000000000010"))).thenReturn(1);
        when(store.createPortariaFlow(Long.parseLong(mixed.guildId()), new CreateFlowRequest("Portaria", "800000000000000011"))).thenReturn(2);
        when(store.readPortaria(Long.parseLong(mixed.guildId()), 1)).thenReturn(response(List.of(flow(1)), flow(1), true, false));
        when(store.readPortaria(Long.parseLong(mixed.guildId()), 2)).thenReturn(response(List.of(flow(2)), flow(2), true, false));

        service.createFlow(authentication, mixed.guildId(), new CreateFlowRequest("Portaria", "800000000000000010"));
        service.createFlow(authentication, mixed.guildId(), new CreateFlowRequest("Portaria", "800000000000000011"));
        verify(store).createPortariaFlow(Long.parseLong(mixed.guildId()), new CreateFlowRequest("Portaria", "800000000000000010"));
        verify(store).createPortariaFlow(Long.parseLong(mixed.guildId()), new CreateFlowRequest("Portaria", "800000000000000011"));
    }

    @Test void rejectsNonTextPortariaDestinationsWithoutWriting() {
        GuildResources mixed = resourcesWithChannels(
            new GuildChannel("800000000000000010", "fichas", "text", null),
            new GuildChannel("800000000000000012", "voz", "voice", null),
            new GuildChannel("800000000000000013", "forum", "forum", null),
            new GuildChannel("800000000000000014", "palco", "stage_voice", null));
        when(access.authorize(authentication, mixed.guildId())).thenReturn(new AuthorizedGuild(42L, mixed));

        for (String invalid : List.of("800000000000000012", "800000000000000013", "800000000000000014", "999999999999999999")) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createFlow(authentication, mixed.guildId(), new CreateFlowRequest("Portaria", invalid)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
            ResponseStatusException resultError = assertThrows(ResponseStatusException.class,
                () -> service.updateResult(authentication, mixed.guildId(), 1, new ResultUpdateRequest("800000000000000010", invalid, null, false)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resultError.getStatusCode());
            ResponseStatusException targetError = assertThrows(ResponseStatusException.class,
                () -> service.updateResult(authentication, mixed.guildId(), 1, new ResultUpdateRequest(invalid, null, null, false)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, targetError.getStatusCode());
            ResponseStatusException rejectedError = assertThrows(ResponseStatusException.class,
                () -> service.updateResult(authentication, mixed.guildId(), 1, new ResultUpdateRequest("800000000000000010", null, invalid, false)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejectedError.getStatusCode());
        }
        verifyNoInteractions(store);
    }

    @Test void rejectsExistingOperationalRoleThatCoddyCannotAssign() {
        GuildResources withUneditableRole = new GuildResources(resources.guildId(),
            List.of(new GuildRole("900000000000000002", "Gerenciado", 20, true, false)),
            resources.channels(), resources.categories(), resources.botCapabilities());
        when(access.authorize(authentication, resources.guildId())).thenReturn(new AuthorizedGuild(42L, withUneditableRole));
        EntryUpdateRequest request = new EntryUpdateRequest(null, "900000000000000002", null, null,
            true, 15, false, 0, false, 30, false, 0);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.updateEntry(authentication, resources.guildId(), null, request));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verifyNoInteractions(store);
    }

    @Test void returnsZeroOneAndAmbiguousFlowStatesWithoutGlobalSelection() {
        PortariaResponse empty = response(List.of(), null, false, false);
        PortariaFlow first = flow(1); PortariaFlow second = flow(2);
        PortariaResponse single = response(List.of(first), first, true, false);
        PortariaResponse ambiguous = response(List.of(first, second), null, true, true);
        when(store.readPortaria(Long.parseLong(resources.guildId()), null)).thenReturn(empty, single, ambiguous);
        assertFalse(service.portaria(authentication, resources.guildId(), null).configured());
        assertEquals(1, service.portaria(authentication, resources.guildId(), null).selectedFlow().id());
        assertTrue(service.portaria(authentication, resources.guildId(), null).ambiguous());
    }

    @Test void proxiesGenericPreviewOnlyAfterValidatingSelectedRoles() {
        StructurePreviewRequest request = new StructurePreviewRequest("private-area", null, "Staff", List.of("staff"), null, List.of("900000000000000001"), false);
        var preview = new ObjectMapper().createObjectNode().put("type", "private-area");
        when(coddy.preview(resources.guildId(), 42L, request)).thenReturn(preview);
        assertEquals("private-area", service.preview(authentication, resources.guildId(), request).get("type").asText());
    }

    @Test void preservesCoddyUnavailableStatus() {
        when(access.authorize(authentication, resources.guildId())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class, () -> service.resources(authentication, resources.guildId())).getStatusCode());
    }

    private static PortariaFlow flow(int id) { return new PortariaFlow(id, "Portaria", "portaria", "800000000000000001", null, null, false); }
    private static com.fasterxml.jackson.databind.JsonNode publicationResult(String disposition) {
        var result = new ObjectMapper().createObjectNode();
        result.putObject("resources")
            .put("welcomeChannelId", "800000000000000001")
            .put("messageId", "810000000000000001");
        var created = result.putArray("created");
        var reused = result.putArray("reused");
        var updated = result.putArray("updated");
        result.putArray("warnings");
        if ("created".equals(disposition)) created.addObject()
            .put("key", "publication").put("id", "810000000000000001");
        if ("reused".equals(disposition)) reused.addObject()
            .put("key", "publication").put("id", "810000000000000001");
        if ("updated".equals(disposition)) updated.addObject()
            .put("key", "publication").put("id", "810000000000000001");
        return result;
    }
    private GuildResources resourcesWithChannels(GuildChannel... channels) {
        return new GuildResources(resources.guildId(), resources.roles(), List.of(channels), resources.categories(), resources.botCapabilities());
    }

    private static GuildConfigurationStore.BumpStoredConfig bumpConfig(String messages, boolean coinsEnabled,
                                                                         Integer coinAmount) {
        return bumpConfig(null, null, messages, coinsEnabled, coinAmount);
    }

    private static GuildConfigurationStore.BumpStoredConfig bumpConfig(String processingChannelId,
                                                                         String legacyWarningSourceChannelId,
                                                                         String messages, boolean coinsEnabled,
                                                                         Integer coinAmount) {
        return new GuildConfigurationStore.BumpStoredConfig(processingChannelId,
            new GuildConfigurationStore.BumpServerSettings(false, legacyWarningSourceChannelId, null, messages, null, null,
                false, null, 0, null, 0, false, null, null, 21, 14, 7, 0, 0, 0),
            coinsEnabled, coinAmount);
    }

    @Test void warnsExactlyOnceWhenPortariaVisitorIsNotFunctionallyAutoJoined() {
        long guildId = Long.parseLong(resources.guildId());
        GuildResources withVisitor = new GuildResources(resources.guildId(),
            List.of(new GuildRole("123", "Visitante", 1, false, true)), resources.channels(), resources.categories(), resources.botCapabilities());
        when(access.authorize(authentication, resources.guildId())).thenReturn(new AuthorizedGuild(42L, withVisitor));
        PortariaConfig visitorConfig = new PortariaConfig(true, null, "123", null, null, true, 15, true, false, 0, false, 30, false, 0);
        when(store.readPortaria(guildId, null)).thenReturn(new PortariaResponse(visitorConfig, List.of(flow(1)), flow(1), List.of(), true, false));

        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(new AutoJoinRolesConfig(true, List.of(123L)));
        assertTrue(service.autoJoin(authentication, resources.guildId()).warnings().isEmpty());

        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(new AutoJoinRolesConfig(true, List.of(456L)));
        assertEquals(1, service.autoJoin(authentication, resources.guildId()).warnings().size());

        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(new AutoJoinRolesConfig(false, List.of(123L)));
        assertEquals(1, service.autoJoin(authentication, resources.guildId()).warnings().size());

        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(new AutoJoinRolesConfig(false, List.of()));
        assertEquals(1, service.autoJoin(authentication, resources.guildId()).warnings().size());
    }

    @Test void doesNotWarnWithoutFunctionalPortariaDependency() {
        long guildId = Long.parseLong(resources.guildId());
        when(store.readPortaria(guildId, null)).thenReturn(response(List.of(), null, false, false));
        when(coddy.autoJoin(resources.guildId(), 42L)).thenReturn(new AutoJoinRolesConfig(false, List.of()));
        assertTrue(service.autoJoin(authentication, resources.guildId()).warnings().isEmpty());
    }

    @Test void deletesValidFlowWhenDiscordPublicationCleanupReturnsTheObserved422() {
        long guildId = Long.parseLong(resources.guildId());
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Operação inválida para o estado atual do servidor"))
            .when(coddy).operation(eq(resources.guildId()), eq(42L), any());
        when(store.deletePortariaFlow(guildId, 1)).thenReturn(1);
        when(store.readPortaria(guildId, null)).thenReturn(response(List.of(), null, false, false));

        DeleteFlowResult result = service.deleteFlow(authentication, resources.guildId(), 1);

        verify(store).requirePortariaFlow(guildId, 1);
        verify(store).deletePortariaFlow(guildId, 1);
        assertFalse(result.portariaConfigured());
        assertEquals(1, result.deletedPublications());
        assertEquals(1, result.warnings().size());
    }

    @Test void rejectsMissingFlowBeforeCallingCoddy() {
        long guildId = Long.parseLong(resources.guildId());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Fluxo da Portaria não encontrado neste servidor"))
            .when(store).requirePortariaFlow(guildId, 1);

        assertThrows(ResponseStatusException.class, () -> service.deleteFlow(authentication, resources.guildId(), 1));

        verifyNoInteractions(coddy);
        verify(store, never()).deletePortariaFlow(anyLong(), anyInt());
    }

    @Test void isolationUsesPersistedVisitorAndDropsCallerIds() {
        GuildOperationRequest request = new GuildOperationRequest("visitor-isolation", null, null, null, null, null, null, null, "evil", "evil", "evil", null, null, null, List.of("evil"), List.of("evil"));
        when(store.readVisitorRoleId(Long.parseLong(resources.guildId()))).thenReturn("900000000000000001");
        var result = new ObjectMapper().createObjectNode();
        when(coddy.operation(eq(resources.guildId()), eq(42L), any())).thenReturn(result);
        service.operation(authentication, resources.guildId(), request);
        var capture = org.mockito.ArgumentCaptor.forClass(GuildOperationRequest.class);
        verify(coddy).operation(eq(resources.guildId()), eq(42L), capture.capture());
        assertEquals("900000000000000001", capture.getValue().visitorRoleId());
        assertEquals(List.of(), capture.getValue().channelIds()); assertEquals(List.of(), capture.getValue().entryChannelIds());
    }
    private static PortariaResponse response(List<PortariaFlow> flows, PortariaFlow selected, boolean configured, boolean ambiguous) {
        PortariaConfig config = new PortariaConfig(true,null,null,null,null,true,15,true,false,0,false,30,false,0);
        return new PortariaResponse(config, flows, selected, List.of(), configured, ambiguous);
    }
}
