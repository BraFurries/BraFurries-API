package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.VipConfigurationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildConfigurationStore.VipStoredConfig;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.BotCapabilities;
import com.Brafurries.API.user.dto.GuildManagementDtos.BotTopRole;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
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
class VipConfigurationServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock GuildConfigurationStore store;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock UserRepository users;
    @Mock CoddyGuildManagementClient coddy;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private VipConfigurationService service;
    private AuthorizedGuild authorized;
    private CommunityDiscord link;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new VipConfigurationService(
            access,
            store,
            discordLinks,
            auditLogs,
            users,
            coddy,
            objectMapper,
            transactionManager
        );

        Community community = new Community();
        community.setId(10);
        community.setName("BraFurries");

        link = new CommunityDiscord();
        link.setId(20);
        link.setCommunity(community);
        link.setGuildId(123L);
        link.setName("BraFurries");
        link.setActive(true);

        actor = new User();
        actor.setId(7);
        actor.setEmail("admin@example.com");
        actor.setDisplayName("Nick");

        GuildRole vip = new GuildRole("10", "VIP", 25, false, true);
        GuildRole top = new GuildRole("20", "Topo VIP", 20, false, true);
        GuildRole bottom = new GuildRole("30", "Base VIP", 10, false, true);
        GuildRole highExternal = new GuildRole("40", "Patrocinador", 60, false, false);

        authorized = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(highExternal, vip, top, bottom),
                List.of(),
                List.of(),
                new BotCapabilities(
                    true,
                    true,
                    true,
                    List.of(),
                    false,
                    true,
                    true,
                    new BotTopRole("999", "Coddy", 50),
                    List.of()
                )
            )
        );
    }

    @Test
    void getUsesLegacyDefaultPrefixWithoutMutatingState() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), null, null, null, false, false, 0L)
        );

        VipConfigResponse response = service.get(auth, "123");

        assertEquals("VIP", response.customRolePrefix());
        assertEquals(List.of("10"), response.roleIds());
        assertEquals("VIP", response.roles().getFirst().name());
        assertFalse(response.runtimeSyncPending());
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void grantingRoleOnlyNeedsToBelongToGuildEvenWhenBotCannotEditIt() {
        stubCommonUpdate();
        when(store.readVipConfig(123L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L))
            .thenReturn(new VipStoredConfig(List.of("40"), "VIP", null, null, false, true, 1L))
            .thenReturn(new VipStoredConfig(List.of("40"), "VIP", null, null, false, false, 1L));
        when(store.markVipReconciled(123L, 1L)).thenReturn(true);

        VipConfigResponse response = service.update(
            auth,
            "123",
            new VipConfigUpdateRequest(
                List.of("40"),
                "VIP",
                new VipCustomRoleRangeRequest(null, null),
                false
            )
        );

        assertEquals(List.of("40"), response.roleIds());
        verify(store).writeVipConfig(123L, List.of("40"), "VIP", null, null, false);
    }

    @Test
    void rejectsRoleFromAnotherGuildBeforePersistence() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of(), "VIP", null, null, false, false, 0L)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(
                auth,
                "123",
                new VipConfigUpdateRequest(
                    List.of("777"),
                    "VIP",
                    new VipCustomRoleRangeRequest(null, null),
                    false
                )
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsBottomAnchorWithoutTopAnchor() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(
                auth,
                "123",
                request("VIP", null, "30", false)
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    @Test
    void rejectsRangeWithoutManageRoles() {
        AuthorizedGuild withoutManageRoles = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                authorized.resources().roles(),
                List.of(),
                List.of(),
                new BotCapabilities(
                    true,
                    false,
                    false,
                    List.of("MANAGE_ROLES"),
                    false,
                    true,
                    true,
                    new BotTopRole("999", "Coddy", 50),
                    List.of()
                )
            )
        );
        when(access.authorize(auth, "123")).thenReturn(withoutManageRoles);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(auth, "123", request("VIP", "20", "30", false))
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsAnchorAboveBotHierarchy() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(auth, "123", request("VIP", "40", null, false))
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void validUpdatePersistsAuditsAndReconcilesAfterCommit() {
        stubCommonUpdate();
        when(store.readVipConfig(123L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L))
            .thenReturn(new VipStoredConfig(List.of("10"), "Apoiador", "20", "30", true, true, 1L))
            .thenReturn(new VipStoredConfig(List.of("10"), "Apoiador", "20", "30", true, false, 1L));
        when(store.markVipReconciled(123L, 1L)).thenReturn(true);

        VipConfigResponse response = service.update(
            auth,
            "123",
            request("Apoiador", "20", "30", true)
        );

        assertEquals("Apoiador", response.customRolePrefix());
        assertEquals("20", response.customRoleRange().topRoleId());
        assertTrue(response.allowStaffColors());
        assertFalse(response.runtimeSyncPending());

        verify(store).writeVipConfig(123L, List.of("10"), "Apoiador", "20", "30", true);
        verify(coddy).reconcileVip("123", 42L);
        verify(store).markVipReconciled(123L, 1L);

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("VIP_CONFIG_UPDATED", audit.getValue().getAction());
        assertEquals("DISCORD_GUILD", audit.getValue().getTargetType());
        assertTrue(audit.getValue().getMetadata().contains("customRolePrefix"));
        assertTrue(audit.getValue().getMetadata().contains("customRoleRange.topRoleId"));
        assertTrue(audit.getValue().getMetadata().contains("allowStaffColors"));
    }

    @Test
    void reconcileFailureDoesNotUndoPersistedConfiguration() {
        stubCommonUpdate();
        when(store.readVipConfig(123L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L))
            .thenReturn(new VipStoredConfig(List.of("10"), "Apoiador", "20", null, false, true, 1L));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy indisponível"))
            .when(coddy).reconcileVip("123", 42L);

        VipConfigResponse response = service.update(
            auth,
            "123",
            request("Apoiador", "20", null, false)
        );

        assertTrue(response.runtimeSyncPending());
        assertFalse(response.warnings().isEmpty());
        verify(store).writeVipConfig(123L, List.of("10"), "Apoiador", "20", null, false);
        verify(store, never()).markVipReconciled(anyLong(), anyLong());
        verify(auditLogs).save(any(CommunityAuditLog.class));
    }

    @Test
    void getPreservesPersistedPendingReconciliationState() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), "VIP", null, null, false, true, 5L)
        );

        VipConfigResponse response = service.get(auth, "123");

        assertTrue(response.runtimeSyncPending());
        verifyNoInteractions(coddy);
    }

    @Test
    void noOpRetryReconcilesWhenPersistedStateIsPending() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, true, 5L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 5L));
        when(store.markVipReconciled(123L, 5L)).thenReturn(true);

        VipConfigResponse response = service.update(
            auth,
            "123",
            request("VIP", null, null, false)
        );

        assertFalse(response.runtimeSyncPending());
        verify(coddy).reconcileVip("123", 42L);
        verify(store).markVipReconciled(123L, 5L);
        verifyNoInteractions(auditLogs);
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void staleReconcileAcknowledgementCannotClearANewerPendingRevision() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L))
            .thenReturn(new VipStoredConfig(List.of("10"), "VIP", null, null, false, true, 5L))
            .thenReturn(new VipStoredConfig(List.of("10"), "Patrono", null, null, false, true, 6L));
        when(store.markVipReconciled(123L, 5L)).thenReturn(false);

        VipConfigResponse response = service.update(
            auth,
            "123",
            request("VIP", null, null, false)
        );

        assertTrue(response.runtimeSyncPending());
        assertEquals("Patrono", response.customRolePrefix());
        assertTrue(response.warnings().stream().anyMatch(value -> value.contains("mais nova")));
        verify(store).markVipReconciled(123L, 5L);
    }

    @Test
    void noOpDoesNotAuditOrCallRuntime() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readVipConfig(123L)).thenReturn(
            new VipStoredConfig(List.of("10"), "VIP", null, null, false, false, 0L)
        );

        VipConfigResponse response = service.update(
            auth,
            "123",
            request("VIP", null, null, false)
        );

        assertFalse(response.runtimeSyncPending());
        verifyNoInteractions(auditLogs, coddy);
        verify(store, never()).writeVipConfig(anyLong(), anyList(), anyString(), any(), any(), anyBoolean());
    }

    private VipConfigUpdateRequest request(
        String prefix,
        String topRoleId,
        String bottomRoleId,
        boolean allowStaffColors
    ) {
        return new VipConfigUpdateRequest(
            List.of("10"),
            prefix,
            new VipCustomRoleRangeRequest(topRoleId, bottomRoleId),
            allowStaffColors
        );
    }

    private void stubCommonUpdate() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }
}
