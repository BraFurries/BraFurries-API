package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.config.ConfigLevels;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.config.ConfigLevelsRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.BotCapabilities;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildChannel;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.dto.XpLevelingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class XpLevelingServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock ConfigLevelsRepository configs;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock UserRepository users;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock CoddyGuildManagementClient coddy;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private XpLevelingService service;
    private ConfigLevels config;
    private User actor;
    private AuthorizedGuild authorized;

    @BeforeEach
    void setUp() {
        service = new XpLevelingService(
            access,
            configs,
            discordLinks,
            users,
            auditLogs,
            coddy,
            objectMapper,
            transactionManager
        );

        Community community = new Community();
        community.setId(10);
        community.setName("BraFurries");

        CommunityDiscord link = new CommunityDiscord();
        link.setId(20);
        link.setCommunity(community);
        link.setGuildId(123L);
        link.setName("BraFurries");
        link.setActive(true);
        link.setUsersQuantity(5500);

        config = config(link);

        actor = new User();
        actor.setId(7);
        actor.setEmail("admin@example.com");
        actor.setDisplayName("Titio");

        GuildResources resources = new GuildResources(
            "123",
            List.of(),
            List.of(new GuildChannel("111", "level-up", "text", null)),
            List.of(),
            new BotCapabilities(true, true, true, List.of())
        );
        authorized = new AuthorizedGuild(42L, resources);
    }

    @Test
    void getUsesRealGuildAuthorizationAndExactGuildConfig() {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        stubConfig();

        XpConfigResponse response = service.get(auth, "123");

        assertEquals("123", response.guildId());
        assertTrue(response.text().enabled());
        assertTrue(response.voice().enabled());
        assertEquals(new BigDecimal("45.000"), response.progression().k());
        assertFalse(response.comboRuntimeActive());
        assertEquals(List.of(
            "{user}", "{member}", "{username}", "{old_level}", "{last_level}",
            "{previous_level}", "{new_level}", "{actual_level}", "{level}"
        ), response.levelUpPlaceholders());
        verify(access).authorize(auth, "123");
        verify(discordLinks).findByGuildId(123L);
        verify(configs).findByGuildId(123L);
    }

    @Test
    void simulationDelegatesToCanonicalCoddyRuntime() throws Exception {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.xpSimulation(eq("123"), eq(42L), any())).thenReturn(
            objectMapper.readTree("""
                {
                  "guildId":"123",
                  "points":[
                    {"level":1,"totalXp":45,"xpFromPreviousLevel":45},
                    {"level":10,"totalXp":4500,"xpFromPreviousLevel":855}
                  ],
                  "curve":{"phase1K":"45","phase1P":"2","phase1B":"0"}
                }
                """)
        );

        XpSimulationResponse response = service.simulate(
            auth,
            "123",
            new XpSimulationRequest(
                new XpProgressionUpdate(
                    new BigDecimal("45"),
                    new BigDecimal("2"),
                    BigDecimal.ZERO
                ),
                List.of(1, 10)
            )
        );

        assertEquals("123", response.guildId());
        assertEquals(4500L, response.points().get(1).totalXp());
        assertEquals(new BigDecimal("45"), response.progression().k());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(coddy).xpSimulation(eq("123"), eq(42L), payload.capture());
        assertTrue(payload.getValue() instanceof java.util.Map);
        assertFalse(payload.getValue().toString().contains("formula"));
    }

    @Test
    void curveUpdateMarksReconciliationAndAuditsOnlyChangedFieldNames() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("ADMIN@EXAMPLE.COM");
        when(users.findByEmail("admin@example.com")).thenReturn(java.util.Optional.of(actor));
        stubConfig();
        stubCanonicalSimulation();
        when(coddy.refreshXpRuntime("123", 42L))
            .thenReturn(objectMapper.readTree("{\"cacheInvalidated\":true}"));

        XpConfigUpdateRequest request = request(new BigDecimal("50"), true, "111");

        XpConfigResponse response = service.update(auth, "123", request);

        assertEquals(new BigDecimal("50"), config.getPhase1K());
        assertTrue(config.getLevelReconcileRequired());
        assertNotNull(config.getLevelReconcileRequestedAt());
        assertFalse(response.runtimeRefreshPending());

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("XP_CONFIG_UPDATED", audit.getValue().getAction());
        assertEquals("DISCORD_GUILD", audit.getValue().getTargetType());
        assertEquals("123", audit.getValue().getTargetId());

        JsonNode metadata = objectMapper.readTree(audit.getValue().getMetadata());
        assertEquals("123", metadata.path("guildId").asText());
        assertEquals(List.of("phase1_k"),
            objectMapper.convertValue(metadata.path("changedFields"), List.class));
        assertFalse(audit.getValue().getMetadata().contains("45.000"));
        assertFalse(audit.getValue().getMetadata().contains("50"));

        verify(coddy).xpSimulation(eq("123"), eq(42L), any());
        verify(coddy).refreshXpRuntime("123", 42L);
    }

    @Test
    void refreshFailureDoesNotPretendDatabaseUpdateWasRolledBack() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(java.util.Optional.of(actor));
        stubConfig();
        stubCanonicalSimulation();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy offline"))
            .when(coddy).refreshXpRuntime("123", 42L);

        XpConfigResponse response = service.update(
            auth,
            "123",
            request(new BigDecimal("50"), true, "111")
        );

        assertTrue(response.runtimeRefreshPending());
        assertEquals(new BigDecimal("50"), config.getPhase1K());
        assertTrue(config.getLevelReconcileRequired());
        verify(configs).saveAndFlush(config);
        verify(auditLogs).save(any(CommunityAuditLog.class));
    }

    @Test
    void newLevelUpChannelMustBelongToAuthorizedGuildAndBeMessageable() {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        stubConfig();

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(
                auth,
                "123",
                request(new BigDecimal("45"), true, "999")
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(coddy, never()).xpSimulation(anyString(), anyLong(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    void inconsistentCapsAreRejectedBeforeRuntimeOrPersistence() {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        stubConfig();

        XpConfigUpdateRequest invalid = new XpConfigUpdateRequest(
            new XpTextUpdate(true, 8, 16, 30, 60),
            new XpVoiceUpdate(
                true, 2, BigDecimal.ZERO, 2, 60, 120,
                new BigDecimal("0.6"), new BigDecimal("0.3")
            ),
            new XpProgressionUpdate(
                new BigDecimal("45"),
                new BigDecimal("2"),
                BigDecimal.ZERO
            ),
            new XpLimitsUpdate(100, 100, 500),
            new XpLevelUpUpdate(true, "111", "Parabéns {member}!"),
            new XpAdvancedUpdate(false, BigDecimal.ONE)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(auth, "123", invalid)
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(coddy, never()).xpSimulation(anyString(), anyLong(), any());
    }

    @Test
    void identicalPutRetriesRuntimeRefreshWithoutWritingAnotherAudit() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(java.util.Optional.of(actor));
        stubConfig();
        stubCanonicalSimulation();
        when(coddy.refreshXpRuntime("123", 42L))
            .thenReturn(objectMapper.readTree("{\"cacheInvalidated\":true}"));

        XpConfigResponse response = service.update(
            auth,
            "123",
            request(new BigDecimal("45.000"), true, "111")
        );

        assertFalse(response.runtimeRefreshPending());
        verify(coddy).refreshXpRuntime("123", 42L);
        verify(auditLogs, never()).save(any(CommunityAuditLog.class));
        verify(configs, never()).saveAndFlush(any(ConfigLevels.class));
    }

    @Test
    void enablingAlertsRevalidatesSameStoredChannelAgainstLiveGuildResources() {
        stubTransactions();
        config.setLevelupWarning(false);
        stubConfig();

        AuthorizedGuild withoutChannel = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(),
                List.of(),
                List.of(),
                new BotCapabilities(true, true, true, List.of())
            )
        );
        when(access.authorize(auth, "123")).thenReturn(withoutChannel);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(
                auth,
                "123",
                request(new BigDecimal("45.000"), true, "111")
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(coddy, never()).xpSimulation(anyString(), anyLong(), any());
        verify(auditLogs, never()).save(any());
    }

    private void stubTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }

    private void stubConfig() {
        when(discordLinks.findByGuildId(123L))
            .thenReturn(java.util.Optional.of(config.getCommunityDiscord()));
        when(configs.findByGuildId(123L))
            .thenReturn(java.util.Optional.of(config));
    }

    private void stubCanonicalSimulation() throws Exception {
        when(coddy.xpSimulation(eq("123"), eq(42L), any())).thenReturn(
            objectMapper.readTree("""
                {
                  "guildId":"123",
                  "points":[{"level":1,"totalXp":50,"xpFromPreviousLevel":50}],
                  "curve":{"phase1K":"50","phase1P":"2","phase1B":"0"}
                }
                """)
        );
    }

    private ConfigLevels config(CommunityDiscord link) {
        ConfigLevels value = new ConfigLevels();
        value.setId(30);
        value.setCommunityDiscord(link);
        value.setClearOnExit(false);
        value.setLevelupWarning(true);
        value.setLevelupWarningChannel(111L);
        value.setLevelUpMessage("Parabéns {member}!");
        value.setMultiplier(BigDecimal.ONE);
        value.setPhase1K(new BigDecimal("45.000"));
        value.setPhase1P(new BigDecimal("2.000"));
        value.setPhase1B(new BigDecimal("0.000"));
        value.setDailyCombo(5);
        value.setComboMultiplier(new BigDecimal("1.250"));
        value.setXpBasePerMin(2);
        value.setVoiceSocialBonusPct(BigDecimal.ZERO);
        value.setVoiceSocialBonusMinHumans(2);
        value.setVoiceDiminishingWindow1Minutes(60);
        value.setVoiceDiminishingWindow2Minutes(120);
        value.setVoiceDiminishingFactor2(new BigDecimal("0.6000"));
        value.setVoiceDiminishingFactor3(new BigDecimal("0.3000"));
        value.setVoiceDailyCapXp(600);
        value.setTextDailyCapXp(300);
        value.setGlobalDailyCapXp(900);
        value.setTextXpEnabled(true);
        value.setVoiceXpEnabled(true);
        value.setTextXpBaseMin(8);
        value.setTextXpBaseMax(16);
        value.setTextXpCooldownMinSeconds(30);
        value.setTextXpCooldownMaxSeconds(60);
        value.setLevelReconcileRequired(false);
        return value;
    }

    private XpConfigUpdateRequest request(
        BigDecimal k,
        boolean levelUpEnabled,
        String levelUpChannelId
    ) {
        return new XpConfigUpdateRequest(
            new XpTextUpdate(true, 8, 16, 30, 60),
            new XpVoiceUpdate(
                true,
                2,
                BigDecimal.ZERO,
                2,
                60,
                120,
                new BigDecimal("0.6000"),
                new BigDecimal("0.3000")
            ),
            new XpProgressionUpdate(k, new BigDecimal("2.000"), BigDecimal.ZERO),
            new XpLimitsUpdate(300, 600, 900),
            new XpLevelUpUpdate(levelUpEnabled, levelUpChannelId, "Parabéns {member}!"),
            new XpAdvancedUpdate(false, BigDecimal.ONE)
        );
    }
}
