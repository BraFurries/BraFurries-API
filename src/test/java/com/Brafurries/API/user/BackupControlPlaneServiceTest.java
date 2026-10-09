package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.BackupDtos.*;
import static com.Brafurries.API.user.dto.GuildManagementDtos.*;
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
import com.Brafurries.API.user.BackupControlPlaneStore.BackupSettingsStored;
import com.Brafurries.API.user.BackupControlPlaneStore.BackupStored;
import com.Brafurries.API.user.BackupControlPlaneStore.RestoreOperationStored;
import com.Brafurries.API.user.BackupControlPlaneStore.SnapshotOperationStored;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
class BackupControlPlaneServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock BackupControlPlaneStore store;
    @Mock CoddyGuildManagementClient coddy;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock UserRepository users;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private BackupControlPlaneService service;
    private AuthorizedGuild authorized;
    private User actor;
    private CommunityDiscord link;

    @BeforeEach
    void setUp() {
        service = new BackupControlPlaneService(
            access,
            store,
            coddy,
            discordLinks,
            auditLogs,
            users,
            objectMapper,
            transactionManager
        );
        authorized = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(),
                List.of(),
                List.of(),
                new BotCapabilities(true, true, true, List.of())
            )
        );

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
    void serverAdminCanListGuildScopedCatalogAndOwnerCanRestore() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.listBackups(123L)).thenReturn(List.of(
            backup(9, 123L, "Antes da reforma", "normal", "req-1")
        ));

        BackupsResponse response = service.list(auth, "123");

        assertTrue(response.canRestore());
        assertEquals(1, response.backups().size());
        assertEquals("MANUAL", response.backups().getFirst().type());
        assertEquals(3, response.backups().getFirst().summary().roles());
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

        verifyNoInteractions(store, coddy);
    }

    @Test
    void serverAdminWhoIsNotGuildOwnerCannotPreviewRestore() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(99L);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.restorePreview(
                auth,
                "123",
                9,
                new RestorePreviewRequest("full")
            )
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(store, never()).findBackup(anyLong(), anyInt());
        verify(coddy, never()).operation(anyString(), anyLong(), any());
    }

    @Test
    void crossGuildBackupIsRejectedBeforeCoddyPreview() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 999)).thenReturn(null);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.restorePreview(
                auth,
                "123",
                999,
                new RestorePreviewRequest("roles")
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(coddy, never()).operation(anyString(), anyLong(), any());
    }

    @Test
    void guildOwnerCanDeleteBackupAndActionIsAudited() {
        stubActorAndTransaction();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(store.hasActiveRestore(123L, 9)).thenReturn(false);
        when(store.deleteBackupIfIdle(123L, 9)).thenReturn(true);

        service.delete(auth, "123", 9);

        verify(store).deleteBackupIfIdle(123L, 9);
        ArgumentCaptor<CommunityAuditLog> audit =
            ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("BACKUP_DELETED", audit.getValue().getAction());
        assertEquals("BACKUP", audit.getValue().getTargetType());
        assertEquals("9", audit.getValue().getTargetId());
        assertTrue(audit.getValue().getMetadata().contains("\"type\":\"MANUAL\""));
    }

    @Test
    void serverAdminWhoIsNotGuildOwnerCannotDeleteBackup() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(99L);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.delete(auth, "123", 9)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(store, never()).findBackup(anyLong(), anyInt());
        verify(store, never()).deleteBackupIfIdle(anyLong(), anyInt());
    }

    @Test
    void backupWithActiveRestoreCannotBeDeleted() {
        stubActorAndTransaction();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "periodic", "req")
        );
        when(store.hasActiveRestore(123L, 9)).thenReturn(true);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.delete(auth, "123", 9)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(store, never()).deleteBackupIfIdle(anyLong(), anyInt());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void deleteReturnsNotFoundIfBackupDisappearsDuringDeleteRace() {
        stubActorAndTransaction();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(store.hasActiveRestore(123L, 9)).thenReturn(false);
        when(store.deleteBackupIfIdle(123L, 9)).thenReturn(false);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.delete(auth, "123", 9)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void deleteRejectsBackupFromAnotherGuild() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 999)).thenReturn(null);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.delete(auth, "123", 999)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(store, never()).deleteBackupIfIdle(anyLong(), anyInt());
    }

    @Test
    void restorePreviewNormalizesCoddyRuntimeContractForWeb() throws Exception {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(coddy.backupRestorePreview("123", 42L, 9, "permissions"))
            .thenReturn(objectMapper.readTree(
                "{\"guildId\":\"123\",\"backupId\":9,\"scope\":\"permissions\","
                    + "\"bot\":{\"topRole\":{\"id\":\"999\",\"name\":\"Coddy\",\"position\":100},"
                    + "\"blockingRoleCount\":1,\"rolesAboveBot\":[{\"id\":\"30\",\"name\":\"Owner\",\"position\":101}]},"
                    + "\"rolePlan\":{\"create\":0,\"update\":0,\"reuse\":2,\"ambiguous\":1,\"skip\":0},"
                    + "\"channelPlan\":{\"create\":0,\"update\":0,\"reuse\":4,\"skip\":0},"
                    + "\"duplicateRoles\":[{\"backupRoleId\":5,\"backupDiscordRoleId\":\"500\",\"name\":\"Staff\","
                    + "\"candidates\":[{\"id\":\"20\",\"name\":\"Staff\",\"position\":10}]}],"
                    + "\"warnings\":[{\"code\":\"UNSUPPORTED_CHANNEL_TYPES\",\"channelTypes\":[15]}],"
                    + "\"blockers\":["
                    + "{\"code\":\"BOT_MISSING_PERMISSIONS\",\"permissions\":[\"MANAGE_ROLES\"]},"
                    + "{\"code\":\"ROLE_RESOLUTION_REQUIRED\",\"backupRoleIds\":[\"5\"]}],"
                    + "\"ready\":false}"
            ));

        BackupRestorePreviewResponse response = service.restorePreview(
            auth,
            "123",
            9,
            new RestorePreviewRequest("permissions")
        );

        assertEquals(9, response.backup().id());
        assertEquals("permissions", response.scope());
        assertTrue(response.bot().available());
        assertEquals("999", response.bot().botTopRole().id());
        assertEquals(List.of("MANAGE_ROLES"), response.bot().missingPermissions());
        assertEquals("30", response.bot().rolesAboveBot().getFirst().id());
        assertEquals(2, response.plan().roles().reuse());
        assertEquals(4, response.plan().channels().reuse());
        assertEquals(5, response.plan().permissions().apply());
        assertEquals("500", response.ambiguousRoles().getFirst().backupDiscordId());
        assertFalse(response.ambiguousRoles().getFirst().canCreateNew());
        assertEquals(2, response.blockers().size());
        assertFalse(response.ready());
        assertTrue(response.warnings().getFirst().message().contains("não são suportados"));
    }


    @Test
    void invalidSettingsAreRejected() {
        when(access.authorize(auth, "123")).thenReturn(authorized);

        ResponseStatusException invalidFrequency = assertThrows(
            ResponseStatusException.class,
            () -> service.updateSettings(
                auth,
                "123",
                new BackupSettingsUpdateRequest(true, "hourly")
            )
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalidFrequency.getStatusCode());

        ResponseStatusException missingEnabled = assertThrows(
            ResponseStatusException.class,
            () -> service.updateSettings(
                auth,
                "123",
                new BackupSettingsUpdateRequest(null, "weekly")
            )
        );
        assertEquals(HttpStatus.BAD_REQUEST, missingEnabled.getStatusCode());

        verify(store, never()).writeSettings(
            anyLong(), anyBoolean(), anyString(), anyInt()
        );
    }


    @Test
    void manualBackupCreatesTrackedOperationAndDispatchesCoddyOnce() {
        stubActorAndTransaction();
        SnapshotOperationStored pending = snapshotOperation(
            70L, 123L, "request-1", "Before", 42L, 7, "PENDING"
        );
        when(store.findSnapshotOperationByKey(123L, "request-1")).thenReturn(null);
        when(store.createOrGetSnapshotOperation(
            123L, "request-1", "Before", 42L, 7
        )).thenReturn(pending);
        when(store.claimSnapshotRequestedAudit(123L, 70L)).thenReturn(true);
        when(coddy.dispatchBackupSnapshot("123", 42L, 70L))
            .thenReturn(objectMapper.createObjectNode());
        when(store.findSnapshotOperation(123L, 70L)).thenReturn(pending);

        SnapshotOperationResponse response = service.create(
            auth,
            "123",
            new CreateBackupRequest("  Before  "),
            "request-1"
        );

        assertEquals(70L, response.id());
        assertEquals("MANUAL", response.backupType());
        assertEquals("PENDING", response.status());
        verify(coddy).dispatchBackupSnapshot("123", 42L, 70L);

        ArgumentCaptor<CommunityAuditLog> audit =
            ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("BACKUP_CREATE_REQUESTED", audit.getValue().getAction());
        assertFalse(audit.getValue().getMetadata().contains("Before"));
    }


    @Test
    void manualBackupIdempotencyRejectsOperationFromAnotherActor() {
        stubActor();
        SnapshotOperationStored winner = snapshotOperation(
            70L, 123L, "request-1", "Snapshot", 99L, 7, "PENDING"
        );
        when(store.findSnapshotOperationByKey(123L, "request-1"))
            .thenReturn(winner);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new CreateBackupRequest("Snapshot"),
                "request-1"
            )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(coddy, never()).dispatchBackupSnapshot(anyString(), anyLong(), anyLong());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void missingIdempotencyKeyIsBadRequestForCreateAndRestore() {
        when(access.authorize(auth, "123")).thenReturn(authorized);

        ResponseStatusException createError = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new CreateBackupRequest("Snapshot"),
                null
            )
        );
        assertEquals(HttpStatus.BAD_REQUEST, createError.getStatusCode());

        when(coddy.getGuildOwner("123")).thenReturn(42L);
        ResponseStatusException restoreError = assertThrows(
            ResponseStatusException.class,
            () -> service.startRestore(
                auth,
                "123",
                9,
                new RestoreStartRequest(
                    "full",
                    objectMapper.createObjectNode(),
                    true
                ),
                null
            )
        );
        assertEquals(HttpStatus.BAD_REQUEST, restoreError.getStatusCode());
    }


    @Test
    void coddyUnavailableDoesNotPretendTrackedSnapshotWasCompleted() {
        stubActorAndTransaction();
        SnapshotOperationStored pending = snapshotOperation(
            70L, 123L, "request-1", "Snapshot", 42L, 7, "PENDING"
        );
        when(store.findSnapshotOperationByKey(123L, "request-1")).thenReturn(null);
        when(store.createOrGetSnapshotOperation(
            123L, "request-1", "Snapshot", 42L, 7
        )).thenReturn(pending);
        when(store.claimSnapshotRequestedAudit(123L, 70L)).thenReturn(false);
        when(coddy.dispatchBackupSnapshot("123", 42L, 70L)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy offline")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.create(
                auth,
                "123",
                new CreateBackupRequest("Snapshot"),
                "request-1"
            )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(store, never()).claimSnapshotCompletionAudit(anyLong(), anyLong());
    }

    @Test
    void hardRestorePreviewBlockerPreventsOperationCreation() throws Exception {
        stubActor();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(coddy.backupRestorePreview("123", 42L, 9, "full")).thenReturn(
            objectMapper.readTree(
                "{\"guildId\":\"123\",\"backupId\":9,"
                    + "\"scope\":\"full\","
                    + "\"blockers\":[{\"code\":\"BOT_ROLE_HIERARCHY\"}],"
                    + "\"duplicateRoles\":[]}"
            )
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.startRestore(
                auth,
                "123",
                9,
                new RestoreStartRequest(
                    "full",
                    objectMapper.createObjectNode(),
                    true
                ),
                "restore-1"
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).createOrGetRestoreOperation(
            anyLong(), anyInt(), anyString(), anyString(),
            anyLong(), any(), anyString()
        );
    }

    @Test
    void permissionsOnlyRestoreRejectsDestructiveRoleMerge() throws Exception {
        stubActor();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findRestoreOperationByKey(123L, "restore-permissions"))
            .thenReturn(null);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(coddy.backupRestorePreview("123", 42L, 9, "permissions"))
            .thenReturn(
                objectMapper.readTree(
                    "{\"guildId\":\"123\",\"backupId\":9,"
                        + "\"scope\":\"permissions\","
                        + "\"blockers\":[{\"code\":\"ROLE_RESOLUTION_REQUIRED\"}],"
                        + "\"duplicateRoles\":[{\"backupRoleId\":5,"
                        + "\"candidates\":[{\"id\":\"20\"},{\"id\":\"21\"}]}]}"
                )
            );
        var decision = objectMapper.createObjectNode();
        decision.put("duplicateStrategy", "merge");

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.startRestore(
                auth,
                "123",
                9,
                new RestoreStartRequest("permissions", decision, true),
                "restore-permissions"
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).createOrGetRestoreOperation(
            anyLong(), anyInt(), anyString(), anyString(),
            anyLong(), any(), anyString()
        );
    }

    @Test
    void restoreReturnsNotFoundIfBackupIsDeletedAfterPreflight() throws Exception {
        stubActor();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findRestoreOperationByKey(123L, "restore-race"))
            .thenReturn(null);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(coddy.backupRestorePreview("123", 42L, 9, "full")).thenReturn(
            objectMapper.readTree(
                "{\"guildId\":\"123\",\"backupId\":9,\"scope\":\"full\","
                    + "\"blockers\":[],\"duplicateRoles\":[]}"
            )
        );
        when(store.createOrGetRestoreOperation(
            eq(123L), eq(9), eq("restore-race"), eq("full"),
            eq(42L), eq(7), anyString()
        )).thenReturn(null);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.startRestore(
                auth,
                "123",
                9,
                new RestoreStartRequest(
                    "full",
                    objectMapper.createObjectNode(),
                    true
                ),
                "restore-race"
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void restoreRetryReusesPersistedOperationWithoutRepeatingPreview() {
        stubActorAndTransaction();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        RestoreOperationStored existing = new RestoreOperationStored(
            70L, 123L, 9, "restore-1", "roles", 42L, 7,
            "PENDING", "{\"duplicateStrategy\":\"merge\"}", null, null,
            0, 0, null,
            Instant.now(), null, null, null, Instant.now()
        );
        when(store.findRestoreOperationByKey(123L, "restore-1"))
            .thenReturn(existing);
        when(store.claimRestoreRequestedAudit(123L, 70L)).thenReturn(false);
        when(coddy.dispatchBackupRestore("123", 42L, 9, 70L, "roles"))
            .thenReturn(objectMapper.createObjectNode());
        when(store.findRestoreOperation(123L, 70L)).thenReturn(existing);

        RestoreOperationResponse response = service.startRestore(
            auth,
            "123",
            9,
            new RestoreStartRequest(
                "roles",
                objectMapper.createObjectNode().put("duplicateStrategy", "merge"),
                true
            ),
            "restore-1"
        );

        assertEquals(70L, response.id());
        verify(coddy, never()).backupRestorePreview(anyString(), anyLong(), anyInt(), anyString());
        verify(store, never()).findBackup(anyLong(), anyInt());
        verify(coddy).dispatchBackupRestore("123", 42L, 9, 70L, "roles");
    }

    @Test
    void explicitDuplicateResolutionRejectsRoleOutsidePreviewCandidates() throws Exception {
        stubActor();
        when(coddy.getGuildOwner("123")).thenReturn(42L);
        when(store.findRestoreOperationByKey(123L, "restore-new")).thenReturn(null);
        when(store.findBackup(123L, 9)).thenReturn(
            backup(9, 123L, "Snapshot", "normal", "req")
        );
        when(coddy.backupRestorePreview("123", 42L, 9, "roles")).thenReturn(
            objectMapper.readTree(
                "{\"guildId\":\"123\",\"backupId\":9,\"scope\":\"roles\","
                    + "\"blockers\":[{\"code\":\"ROLE_RESOLUTION_REQUIRED\"}],"
                    + "\"duplicateRoles\":[{\"backupRoleId\":5,"
                    + "\"candidates\":[{\"id\":\"20\"},{\"id\":\"21\"}]}]}"
            )
        );
        var decision = objectMapper.createObjectNode();
        decision.put("duplicateStrategy", "explicit");
        decision.putObject("roleResolutions").put("5", "999");

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.startRestore(
                auth,
                "123",
                9,
                new RestoreStartRequest("roles", decision, true),
                "restore-new"
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).createOrGetRestoreOperation(
            anyLong(), anyInt(), anyString(), anyString(),
            anyLong(), any(), anyString()
        );
    }

    @Test
    void terminalRestoreIsAuditedWithOriginalActorAndNotPollingUser() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        RestoreOperationStored operation = new RestoreOperationStored(
            70L, 123L, 9, "restore-1", "full", 42L, 7,
            "PARTIAL", "{}", "{\"status\":\"PARTIAL\"}", null,
            4, 4, "PARTIAL",
            Instant.now(), null, Instant.now(), Instant.now(), Instant.now()
        );
        when(store.findRestoreOperation(123L, 70L))
            .thenReturn(operation)
            .thenReturn(operation);
        when(users.findById(7)).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(store.claimRestoreCompletionAudit(123L, 70L)).thenReturn(true);
        stubTransaction();

        RestoreOperationResponse response =
            service.restoreOperation(auth, "123", 70L);

        assertEquals("PARTIAL", response.status());
        ArgumentCaptor<CommunityAuditLog> audit =
            ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("BACKUP_RESTORE_COMPLETED", audit.getValue().getAction());
        assertEquals(actor, audit.getValue().getActorUser());
        assertTrue(audit.getValue().getMetadata().contains("\"status\":\"PARTIAL\""));
    }

    private SnapshotOperationStored snapshotOperation(
        long id,
        long guildId,
        String key,
        String name,
        long discordUserId,
        Integer actorUserId,
        String status
    ) {
        return new SnapshotOperationStored(
            id,
            guildId,
            key,
            "normal",
            name,
            discordUserId,
            actorUserId,
            status,
            null,
            0,
            4,
            status.equals("PENDING") ? null : status,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-10-07T04:43:00Z")
        );
    }

    private BackupStored backup(
        int id,
        long guildId,
        String name,
        String type,
        String key
    ) {
        return new BackupStored(
            id,
            guildId,
            name,
            type,
            42L,
            key,
            null,
            Instant.parse("2026-10-07T04:43:00Z"),
            3,
            4,
            5
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
        stubTransaction();
    }

    private void stubTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }
}
