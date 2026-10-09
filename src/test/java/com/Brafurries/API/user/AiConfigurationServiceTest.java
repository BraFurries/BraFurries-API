package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.AiConfigurationDtos.*;
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
import com.Brafurries.API.user.GuildConfigurationStore.AiStoredConfig;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.BotCapabilities;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildChannel;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
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
class AiConfigurationServiceTest {
    @Mock GuildManagementAccessService access;
    @Mock GuildConfigurationStore store;
    @Mock OpenAiCredentialValidator openAi;
    @Mock AiCredentialCipher credentialCipher;
    @Mock CommunityDiscordRepository discordLinks;
    @Mock CommunityAuditLogRepository auditLogs;
    @Mock UserRepository users;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock Authentication auth;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AiConfigurationService service;
    private AuthorizedGuild authorized;
    private CommunityDiscord link;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new AiConfigurationService(
            access,
            store,
            openAi,
            credentialCipher,
            discordLinks,
            auditLogs,
            users,
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

        authorized = new AuthorizedGuild(
            42L,
            new GuildResources(
                "123",
                List.of(),
                List.of(
                    new GuildChannel("111", "geral", "text", null),
                    new GuildChannel("112", "avisos", "news", null),
                    new GuildChannel("222", "voz", "voice", null)
                ),
                List.of(),
                new BotCapabilities(true, true, true, List.of())
            )
        );
    }

    @Test
    void getNeverReturnsTokenOrCiphertext() throws Exception {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(true));

        AiConfigResponse response = service.get(auth, "123");
        String json = objectMapper.writeValueAsString(response);

        assertEquals("123", response.guildId());
        assertTrue(response.tokenConfigured());
        assertNull(response.tokenUpdatedAt());
        assertFalse(json.contains("openaiToken"));
        assertFalse(json.contains("encryptedToken"));
        assertFalse(json.contains("ciphertext"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void updateRejectsChannelOutsideAuthorizedGuildBeforePersistence() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(false));

        AiConfigUpdateRequest request = request(
            "gpt-4.1-mini",
            true,
            List.of("999"),
            List.of()
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.update(auth, "123", request)
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(store, never()).writeAiConfig(anyLong(), anyBoolean(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyList());
        verifyNoInteractions(openAi, credentialCipher);
    }

    @Test
    void modelChangeWithStoredTokenIsValidatedBeforeWriteAndAuditsOnlyFieldNames() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L))
            .thenReturn(current(true))
            .thenReturn(new AiStoredConfig(
                true,
                "gpt-5-mini",
                true,
                null,
                true,
                true,
                List.of("42"),
                List.of("111")
            ));
        when(auth.getName()).thenReturn("ADMIN@EXAMPLE.COM");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(store.readAiTokenCiphertext(123L)).thenReturn("sealed-existing");
        when(credentialCipher.decrypt("sealed-existing")).thenReturn("existing-secret");

        AiConfigResponse response = service.update(
            auth,
            "123",
            request("gpt-5-mini", true, List.of("111", "111"), List.of("42", "42"))
        );

        assertEquals("gpt-5-mini", response.model());
        verify(credentialCipher).decrypt("sealed-existing");
        verify(openAi).validate("existing-secret", "gpt-5-mini");
        verify(store).writeAiConfig(
            123L,
            true,
            "gpt-5-mini",
            true,
            List.of("111"),
            true,
            List.of("42")
        );

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("AI_CONFIG_UPDATED", audit.getValue().getAction());
        assertEquals("DISCORD_GUILD", audit.getValue().getTargetType());
        JsonNode metadata = objectMapper.readTree(audit.getValue().getMetadata());
        assertEquals("123", metadata.path("guildId").asText());
        assertTrue(metadata.path("changedFields").toString().contains("model"));
        assertFalse(audit.getValue().getMetadata().contains("token"));
    }

    @Test
    void tokenRotationPersistsSealedCredentialAndAuditInOneLocalTransaction() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(true));
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        when(credentialCipher.encrypt("super-secret-token")).thenReturn("sealed-ciphertext");
        LocalDateTime persistedAt = LocalDateTime.parse("2026-10-06T01:02:03.123456");
        when(store.writeAiTokenCredential(123L, "sealed-ciphertext")).thenReturn(persistedAt);

        AiTokenStatusResponse response = service.rotateToken(
            auth,
            "123",
            new AiTokenUpdateRequest("super-secret-token")
        );

        assertTrue(response.configured());
        assertEquals(persistedAt, response.updatedAt());
        verify(openAi).validate("super-secret-token", "gpt-4.1-mini");
        verify(credentialCipher).encrypt("super-secret-token");
        verify(store).writeAiTokenCredential(123L, "sealed-ciphertext");

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("AI_TOKEN_ROTATED", audit.getValue().getAction());
        assertFalse(audit.getValue().getMetadata().contains("super-secret-token"));
        assertFalse(audit.getValue().getMetadata().contains("sealed-ciphertext"));
        assertFalse(audit.getValue().getMetadata().toLowerCase().contains("token"));
    }

    @Test
    void rejectedCredentialIsNeverEncryptedOrPersisted() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(true));
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "rejected"))
            .when(openAi).validate("bad-secret", "gpt-4.1-mini");

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.rotateToken(auth, "123", new AiTokenUpdateRequest("bad-secret"))
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(credentialCipher, never()).encrypt(anyString());
        verify(store, never()).writeAiTokenCredential(anyLong(), anyString());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void tokenRotationFailsBeforeProviderWhenAuditLinkIsMissing() {
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(true));
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.rotateToken(
                auth,
                "123",
                new AiTokenUpdateRequest("must-not-leave-api")
            )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(openAi, credentialCipher);
        verify(store, never()).writeAiTokenCredential(anyLong(), anyString());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void deniedGuildNeverReachesCredentialProvider() {
        when(access.authorize(auth, "999"))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissão"));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.rotateToken(auth, "999", new AiTokenUpdateRequest("do-not-send"))
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(openAi, credentialCipher);
        verifyNoInteractions(store);
        verifyNoInteractions(auditLogs);
    }

    @Test
    void removalPersistsAndAuditsAtomicallyWithoutProviderMutation() throws Exception {
        stubTransactions();
        when(access.authorize(auth, "123")).thenReturn(authorized);
        when(store.readAiConfig(123L)).thenReturn(current(true));
        when(auth.getName()).thenReturn("admin@example.com");
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(discordLinks.findByGuildId(123L)).thenReturn(Optional.of(link));

        AiTokenStatusResponse response = service.removeToken(auth, "123");

        assertFalse(response.configured());
        assertNull(response.updatedAt());
        verify(store).removeAiTokenCredential(123L);
        verifyNoInteractions(openAi, credentialCipher);

        ArgumentCaptor<CommunityAuditLog> audit = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertEquals("AI_TOKEN_REMOVED", audit.getValue().getAction());
    }

    private AiStoredConfig current(boolean tokenConfigured) {
        return new AiStoredConfig(
            true,
            "gpt-4.1-mini",
            tokenConfigured,
            null,
            true,
            true,
            List.of("42"),
            List.of("111")
        );
    }

    private AiConfigUpdateRequest request(
        String model,
        boolean channelLimitEnabled,
        List<String> channelIds,
        List<String> adminIds
    ) {
        return new AiConfigUpdateRequest(
            true,
            model,
            channelLimitEnabled,
            channelIds,
            true,
            adminIds
        );
    }

    private void stubTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
    }
}
