package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.GuildManagementDtos.*;
import static com.Brafurries.API.user.dto.ModerationAccessDtos.*;
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
import com.Brafurries.API.user.GuildConfigurationStore.CollaborativeModerationStored;
import com.Brafurries.API.user.GuildConfigurationStore.PortariaBypassStored;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class ModerationAccessServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock GuildConfigurationStore store;
    @Mock CoddyGuildManagementClient coddy;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock UserRepository users;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private ModerationAccessService service;
    private AuthorizedGuild authorized;
    private CommunityDiscord link;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new ModerationAccessService(
            access,
            store,
            coddy,
            discordLinks,
            auditLogs,
            users,
            new ObjectMapper().findAndRegisterModules(),
            transactionManager
        );

        authorized = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(
                    new GuildRole("10", "Staff", 10, false, true),
                    new GuildRole("11", "Moderador", 9, false, true)
                ),
                List.of(),
                List.of(),
                new BotCapabilities(true, true, true, List.of())
            )
        );

        Community community = new Community();
        community.setId(5);
        community.setName("BraFurries");
        link = new CommunityDiscord();
        link.setId(6);
        link.setGuildId(123L);
        link.setName("BraFurries");
        link.setActive(true);
        link.setCommunity(community);

        actor = new User();
        actor.setId(7);
        actor.setEmail("admin@example.com");
        actor.setDisplayName("Nick");
    }

    @Test
    void rejectsStaffRoleFromAnotherGuildBeforePersistence() {
        when(access.authorize(auth, "123")).thenReturn(authorized);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.updateStaff(auth, "123", new StaffRolesRequest(List.of("999")))
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeStaffRoleIds(anyLong(), anyList());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void collaborativeModerationExposesExistingRuntimeParticipationRuleAndAuditsUpdate() {
        stubActor();
        stubTransaction();
        when(store.readCollaborativeModeration(123L))
            .thenReturn(new CollaborativeModerationStored(false, null, 3))
            .thenReturn(new CollaborativeModerationStored(true, "🧹", 4));
        when(store.readStaffRoleIds(123L)).thenReturn(List.of("10"));

        ModerationAccessResponse response = service.updateCollaborativeModeration(
            auth,
            "123",
            new CollaborativeModerationUpdateRequest(true, "🧹", 4)
        );

        assertTrue(response.collaborativeModeration().enabled());
        assertEquals("ALL_NON_BOT_MEMBERS", response.collaborativeModeration().participantMode());
        assertEquals(List.of("10"), response.collaborativeModeration().protectedStaffRoleIds());
        verify(store).writeCollaborativeModeration(123L, true, "🧹", 4);

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("COLLABORATIVE_MODERATION_UPDATED", audit.getValue().getAction());
    }

    @Test
    void accountBypassRequiresLiveMemberInTheAuthorizedGuild() throws Exception {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(coddy.operation(eq("123"), eq(42L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Operação inválida")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.createBypass(
                auth,
                "123",
                new PortariaBypassCreateRequest(
                    "account", "999", "completo", false, true, null
                )
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).upsertAccountBypass(anyLong(), anyLong(), anyString(), anyBoolean(),
            anyBoolean(), any());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void createsGuildScopedAccountBypassAfterCoddyConfirmsTargetAndAuditsWithoutTargetValue() throws Exception {
        stubActor();
        stubTransaction();
        when(coddy.operation(eq("123"), eq(42L), any())).thenReturn(
            new ObjectMapper().readTree(
                "{\"bypassType\":\"account\",\"value\":\"555\",\"displayName\":\"Member\"}"
            )
        );
        when(store.upsertAccountBypass(
            eq(123L), eq(555L), eq("provisorio"), eq(false), eq(true),
            any(LocalDateTime.class)
        )).thenReturn(91L);

        Instant expires = Instant.now().plusSeconds(2 * 24 * 60 * 60);
        LocalDateTime storedExpires = LocalDateTime.ofInstant(expires, ZoneOffset.UTC);
        when(store.readPortariaEnabled(123L)).thenReturn(true);
        when(store.findPortariaBypass(123L, "account", 91L)).thenReturn(
            new PortariaBypassStored(
                91L, "account", "555", "provisorio", false, true,
                storedExpires, null, null, LocalDateTime.now(), LocalDateTime.now()
            )
        );

        PortariaBypassResponse response = service.createBypass(
            auth,
            "123",
            new PortariaBypassCreateRequest(
                "account", "555", "provisorio", false, true, expires
            )
        );

        assertEquals("555", response.value());
        assertEquals("provisorio", response.accessMode());
        assertTrue(response.active());
        assertTrue(response.effective());
        verify(store).upsertAccountBypass(
            eq(123L), eq(555L), eq("provisorio"), eq(false), eq(true),
            argThat(value -> value != null && value.equals(storedExpires))
        );

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("PORTARIA_BYPASS_CREATED", audit.getValue().getAction());
        assertFalse(audit.getValue().getMetadata().contains("555"));
    }

    @Test
    void listRecordsDueExpirationAndKeepsItTenantScoped() {
        stubActor();
        stubTransaction();
        PortariaBypassStored due = new PortariaBypassStored(
            8L, "invite", "abc", null, null, true,
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, null,
            LocalDateTime.now(ZoneOffset.UTC).minusDays(1), LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        );
        when(store.expireDuePortariaBypasses(123L)).thenReturn(List.of(due));
        when(store.readPortariaEnabled(123L)).thenReturn(true);
        when(store.readPortariaBypasses(123L)).thenReturn(List.of(
            new PortariaBypassStored(
                8L, "invite", "abc", null, null, false,
                due.expiresAt(), LocalDateTime.now(), null,
                due.createdAt(), LocalDateTime.now()
            )
        ));

        PortariaBypassesResponse response = service.bypasses(auth, "123");

        assertTrue(response.portariaEnabled());
        assertEquals(1, response.bypasses().size());
        assertTrue(response.bypasses().getFirst().expired());
        assertFalse(response.bypasses().getFirst().active());
        verify(store).expireDuePortariaBypasses(123L);

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("PORTARIA_BYPASS_EXPIRED", audit.getValue().getAction());
        assertFalse(audit.getValue().getMetadata().contains("abc"));
    }

    @Test
    void reactivatingInactiveBypassRequiresFreshGuildValidation() {
        stubActor();
        when(store.findPortariaBypass(123L, "invite", 77L)).thenReturn(
            new PortariaBypassStored(
                77L, "invite", "StaleCode", null, null, false,
                null, null, null,
                LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC)
            )
        );
        when(coddy.operation(eq("123"), eq(42L), any())).thenThrow(
            new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invite inválido")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.updateBypass(
                auth,
                "123",
                "invite",
                77L,
                new PortariaBypassUpdateRequest(true, null, null, null, null)
            )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).updateInviteBypass(anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    void configuredBypassIsNotEffectiveWhenPortariaIsDisabled() {
        stubActor();
        stubTransaction();
        when(store.readPortariaEnabled(123L)).thenReturn(false);
        when(store.readPortariaBypasses(123L)).thenReturn(List.of(
            new PortariaBypassStored(
                12L, "invite", "CaseSensitive", null, null, true,
                null, null, null,
                LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC)
            )
        ));

        PortariaBypassesResponse response = service.bypasses(auth, "123");

        assertFalse(response.portariaEnabled());
        assertTrue(response.bypasses().getFirst().active());
        assertFalse(response.bypasses().getFirst().effective());
        assertFalse(response.bypasses().getFirst().expired());
    }

    @Test
    void unauthorizedGuildStopsBeforeBypassStorageOrRuntimeValidation() {
        when(access.authorize(auth, "456")).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão")
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.bypasses(auth, "456")
        );

        verifyNoInteractions(store, coddy, auditLogs);
    }

    private void stubActor() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
    }

    private void stubTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }
}
