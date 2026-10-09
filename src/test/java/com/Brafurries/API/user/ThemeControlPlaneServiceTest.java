package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.GuildManagementDtos.*;
import static com.Brafurries.API.user.dto.ThemeDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.ThemeControlPlaneStore.ApplicationStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.OperationStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ResourceChangeStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ThemeAssetStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ThemeStored;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
class ThemeControlPlaneServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock ThemeControlPlaneStore store;
    @Mock CoddyGuildManagementClient coddy;
    @Mock ThemeAssetStorageService assets;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock UserRepository users;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ThemeControlPlaneService service;
    private AuthorizedGuild authorized;
    private User actor;
    private CommunityDiscord link;

    @BeforeEach
    void setUp() {
        service = new ThemeControlPlaneService(
            access,
            store,
            coddy,
            assets,
            discordLinks,
            auditLogs,
            users,
            objectMapper,
            transactionManager
        );
        authorized = authorizedGuild();

        actor = new User();
        actor.setId(7);
        actor.setEmail("admin@example.com");
        actor.setDisplayName("Admin");

        Community community = new Community();
        community.setId(5);
        community.setName("Community");
        link = new CommunityDiscord();
        link.setId(6);
        link.setGuildId(123L);
        link.setName("Guild");
        link.setActive(true);
        link.setCommunity(community);
    }

    @Test
    void communityAuthorityCannotBypassDiscordGuildAuthorization() {
        when(access.authorize(auth, "123")).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem autoridade Discord")
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.list(auth, "123")
        );

        verifyNoInteractions(store, coddy, assets);
    }

    @Test
    void resourcesExposeVipAndManagedRolesAsUnavailableButNormalRolesRemainEligible() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.vipCustomRoleIds(123L)).thenReturn(Set.of(202L));

        ThemeResourcesResponse response = service.resources(auth, "123");

        ThemeEditableRole staff = response.roles().stream()
            .filter(role -> role.id().equals("201"))
            .findFirst()
            .orElseThrow();
        ThemeEditableRole vip = response.roles().stream()
            .filter(role -> role.id().equals("202"))
            .findFirst()
            .orElseThrow();
        ThemeEditableRole managed = response.roles().stream()
            .filter(role -> role.id().equals("203"))
            .findFirst()
            .orElseThrow();

        assertTrue(staff.eligible());
        assertNull(staff.unavailableReason());
        assertFalse(vip.eligible());
        assertEquals("CODDY_VIP_CUSTOM_ROLE", vip.unavailableReason());
        assertFalse(managed.eligible());
        assertEquals("MANAGED_BY_DISCORD", managed.unavailableReason());
    }

    @Test
    void emptyThemeDefinitionIsRejectedBeforePersistence() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.vipCustomRoleIds(123L)).thenReturn(Set.of());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new ThemeSaveRequest(
                    "Sem mudanças",
                    null,
                    "UNCHANGED",
                    "UNCHANGED",
                    List.of()
                )
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).createTheme(
            anyLong(), anyString(), any(), anyLong(), any(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void resourceFromAnotherGuildIsRejectedAtControlPlane() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.vipCustomRoleIds(123L)).thenReturn(Set.of());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new ThemeSaveRequest(
                    "Halloween",
                    null,
                    "UNCHANGED",
                    "UNCHANGED",
                    List.of(new ThemeResourceRequest(
                        "CHANNEL",
                        "999999",
                        "assombrado"
                    ))
                )
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).createTheme(
            anyLong(), anyString(), any(), anyLong(), any(), anyString(), anyString(), anyList()
        );
    }

    @Test
    void vipCustomRoleCannotBePersistedInTheme() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.vipCustomRoleIds(123L)).thenReturn(Set.of(202L));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new ThemeSaveRequest(
                    "Halloween",
                    null,
                    "UNCHANGED",
                    "UNCHANGED",
                    List.of(new ThemeResourceRequest(
                        "ROLE",
                        "202",
                        "Vampiro Nick"
                    ))
                )
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertTrue(error.getReason().contains("CODDY_VIP_CUSTOM_ROLE"));
    }

    @Test
    void createIsGuildScopedAndAudited() {
        stubActorAndTransaction();
        when(store.vipCustomRoleIds(123L)).thenReturn(Set.of());
        when(store.createTheme(
            eq(123L),
            eq("Halloween"),
            eq("Evento"),
            eq(42L),
            eq(7),
            eq("UNCHANGED"),
            eq("UNCHANGED"),
            anyList()
        )).thenReturn(7L);
        when(store.findTheme(123L, 7L)).thenReturn(theme());
        when(store.listResourceChanges(7L)).thenReturn(List.of(
            new ResourceChangeStored(1L, 7L, "CHANNEL", 101L, "assombrado")
        ));

        ThemeResponse response = service.create(
            auth,
            "123",
            new ThemeSaveRequest(
                "Halloween",
                "Evento",
                "UNCHANGED",
                "UNCHANGED",
                List.of(new ThemeResourceRequest(
                    "CHANNEL",
                    "101",
                    "assombrado"
                ))
            )
        );

        assertEquals(7L, response.id());
        assertEquals("123", response.guildId());
        verify(auditLogs).save(argThat(audit ->
            "THEME_CREATED".equals(audit.getAction())
                && "DISCORD_THEME".equals(audit.getTargetType())
                && "7".equals(audit.getTargetId())
        ));
    }

    @Test
    void deletedThemeAssetCleanupCanBeRetriedWithoutDuplicatingDeleteAudit() {
        stubActorAndTransaction();
        ThemeAssetStored asset = new ThemeAssetStored(
            901L,
            7L,
            123L,
            "ICON",
            "themes/123/7/definition/icon/original.png",
            "image/png",
            "a".repeat(64),
            128L,
            Instant.parse("2026-10-07T19:00:00Z")
        );
        when(store.findThemeIncludingDeleted(123L, 7L))
            .thenReturn(theme(), deletedTheme());
        when(store.softDeleteTheme(123L, 7L)).thenReturn(true);
        when(store.listThemeAssets(123L, 7L))
            .thenReturn(List.of(asset), List.of(asset));
        doThrow(new RuntimeException("transient R2 failure"))
            .doNothing()
            .when(assets)
            .deleteDefinition(123L, 7L, asset.storageKey());

        service.delete(auth, "123", 7L);
        service.delete(auth, "123", 7L);

        verify(store, times(1)).softDeleteTheme(123L, 7L);
        verify(assets, times(2)).deleteDefinition(123L, 7L, asset.storageKey());
        verify(store, times(1)).deleteThemeAsset(123L, 7L, 901L);
        verify(auditLogs, times(1)).save(argThat(audit ->
            "THEME_DELETED".equals(audit.getAction())
        ));
    }

    @Test
    void requestedAuditClaimRollsBackWhenAuditPersistenceFails() {
        stubActorAndTransaction();
        when(store.findOperationByKey(123L, "apply-audit-failure")).thenReturn(null);
        when(store.findTheme(123L, 7L)).thenReturn(theme());
        when(store.findActiveApplication(123L)).thenReturn(null);
        when(store.listResourceChanges(7L)).thenReturn(List.of(
            new ResourceChangeStored(1L, 7L, "CHANNEL", 101L, "assombrado")
        ));
        when(store.createApplyApplication(
            eq(123L),
            eq(7L),
            eq(42L),
            eq(7),
            eq("apply-audit-failure"),
            anyString()
        )).thenReturn(operation("PENDING", "APPLY", false));
        when(store.findApplication(123L, 70L)).thenReturn(application("APPLYING"));
        when(store.claimOperationRequestedAudit(123L, 80L)).thenReturn(true);
        doThrow(new RuntimeException("audit persistence failed"))
            .when(auditLogs)
            .save(any());

        assertThrows(
            RuntimeException.class,
            () -> service.apply(
                auth,
                "123",
                7L,
                new ThemeApplyRequest(true),
                "apply-audit-failure"
            )
        );

        verify(transactionManager, atLeastOnce()).rollback(transactionStatus);
        verify(coddy, never()).dispatchThemeApply(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void applyFreezesCurrentDefinitionAndDispatchesPersistedOperation() {
        stubActorAndTransaction();
        when(store.findOperationByKey(123L, "apply-1")).thenReturn(null);
        when(store.findTheme(123L, 7L)).thenReturn(theme());
        when(store.findActiveApplication(123L)).thenReturn(null);
        when(store.listResourceChanges(7L)).thenReturn(List.of(
            new ResourceChangeStored(1L, 7L, "CHANNEL", 101L, "assombrado")
        ));
        when(store.createApplyApplication(
            eq(123L),
            eq(7L),
            eq(42L),
            eq(7),
            eq("apply-1"),
            anyString()
        )).thenReturn(operation("PENDING", "APPLY", false));
        when(store.findApplication(123L, 70L)).thenReturn(application("APPLYING"));
        when(store.claimOperationRequestedAudit(123L, 80L)).thenReturn(true);
        when(store.findOperation(123L, 80L)).thenReturn(operation("RUNNING", "APPLY", false));
        when(store.listOperationSteps(123L, 80L)).thenReturn(List.of());
        when(coddy.dispatchThemeApply("123", 42L, 70L, 80L))
            .thenReturn(objectMapper.createObjectNode().put("accepted", true));

        ThemeOperationResponse response = service.apply(
            auth,
            "123",
            7L,
            new ThemeApplyRequest(true),
            "apply-1"
        );

        ArgumentCaptor<String> frozen = ArgumentCaptor.forClass(String.class);
        verify(store).createApplyApplication(
            eq(123L),
            eq(7L),
            eq(42L),
            eq(7),
            eq("apply-1"),
            frozen.capture()
        );
        assertTrue(frozen.getValue().contains("\"resourceId\":\"101\""));
        assertTrue(frozen.getValue().contains("\"targetName\":\"assombrado\""));
        verify(coddy).dispatchThemeApply("123", 42L, 70L, 80L);
        assertEquals("RUNNING", response.status());
    }

    @Test
    void secondThemeCannotApplyWhileGuildAlreadyHasActiveApplication() {
        stubActor();
        when(store.findOperationByKey(123L, "apply-2")).thenReturn(null);
        when(store.findTheme(123L, 7L)).thenReturn(theme());
        when(store.findActiveApplication(123L)).thenReturn(application("ACTIVE"));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.apply(
                auth,
                "123",
                7L,
                new ThemeApplyRequest(true),
                "apply-2"
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(store, never()).createApplyApplication(
            anyLong(), anyLong(), anyLong(), any(), anyString(), anyString()
        );
    }

    @Test
    void restoreIsBlockedWhileApplicationIsStillApplying() {
        stubActor();
        when(store.findOperationByKey(123L, "restore-1")).thenReturn(null);
        when(store.findActiveApplication(123L)).thenReturn(application("APPLYING"));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.restore(
                auth,
                "123",
                70L,
                new ThemeRestoreRequest(true, false),
                "restore-1"
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(store, never()).createRestoreOperation(
            anyLong(), anyLong(), anyLong(), any(), anyString(), anyBoolean()
        );
    }

    @Test
    void applyFailsCleanlyIfThemeWasDeletedDuringCreationRace() {
        stubActor();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
        when(store.findOperationByKey(123L, "apply-race")).thenReturn(null);
        when(store.findTheme(123L, 7L)).thenReturn(theme());
        when(store.listResourceChanges(7L)).thenReturn(List.of(
            new ResourceChangeStored(1L, 7L, "CHANNEL", 101L, "assombrado")
        ));
        when(store.findActiveApplication(123L)).thenReturn(null);
        when(store.createApplyApplication(
            anyLong(), anyLong(), anyLong(), any(), anyString(), anyString()
        )).thenReturn(null);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.apply(
                auth,
                "123",
                7L,
                new ThemeApplyRequest(true),
                "apply-race"
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(coddy, never()).dispatchThemeApply(anyString(), anyLong(), anyLong(), anyLong());
    }

    private AuthorizedGuild authorizedGuild() {
        return new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(
                    new GuildRole("201", "Moderador", 10, false, true),
                    new GuildRole("202", "VIP Nick", 9, false, true),
                    new GuildRole("203", "Integration", 8, true, false)
                ),
                List.of(new GuildChannel("101", "geral", "text", null)),
                List.of(new GuildCategory("301", "Eventos")),
                new BotCapabilities(true, true, true, List.of())
            )
        );
    }

    private ThemeStored theme() {
        Instant now = Instant.parse("2026-10-07T19:00:00Z");
        return new ThemeStored(
            7L,
            123L,
            "Halloween",
            "Evento",
            "UNCHANGED",
            null,
            null,
            null,
            null,
            "UNCHANGED",
            null,
            null,
            null,
            null,
            42L,
            7,
            null,
            now,
            now
        );
    }

    private ThemeStored deletedTheme() {
        ThemeStored current = theme();
        return new ThemeStored(
            current.id(),
            current.guildId(),
            current.name(),
            current.description(),
            current.iconAction(),
            current.iconAssetKey(),
            current.iconAssetContentType(),
            current.iconAssetSha256(),
            current.iconAssetSizeBytes(),
            current.bannerAction(),
            current.bannerAssetKey(),
            current.bannerAssetContentType(),
            current.bannerAssetSha256(),
            current.bannerAssetSizeBytes(),
            current.createdByDiscordUserId(),
            current.createdByUserId(),
            Instant.parse("2026-10-07T20:00:00Z"),
            current.createdAt(),
            current.updatedAt()
        );
    }

    private ApplicationStored application(String status) {
        Instant now = Instant.parse("2026-10-07T19:00:00Z");
        return new ApplicationStored(
            70L,
            7L,
            123L,
            42L,
            7,
            status,
            "{\"guildId\":\"123\",\"themeId\":7,\"name\":\"Halloween\","
                + "\"icon\":{\"action\":\"UNCHANGED\"},"
                + "\"banner\":{\"action\":\"UNCHANGED\"},"
                + "\"resources\":[{\"resourceType\":\"CHANNEL\","
                + "\"resourceId\":\"101\",\"targetName\":\"assombrado\"}]}",
            status.equals("ACTIVE") ? now : null,
            null,
            null,
            null,
            now,
            now
        );
    }

    private OperationStored operation(
        String status,
        String type,
        boolean force
    ) {
        Instant now = Instant.parse("2026-10-07T19:00:00Z");
        return new OperationStored(
            80L,
            70L,
            123L,
            "operation-key",
            type,
            42L,
            7,
            force,
            status,
            0,
            3,
            "STARTING",
            null,
            null,
            null,
            null,
            status.equals("PENDING") ? null : now,
            null,
            now,
            now
        );
    }

    private void stubActor() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
    }

    private void stubActorAndTransaction() {
        stubActor();
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }
}
