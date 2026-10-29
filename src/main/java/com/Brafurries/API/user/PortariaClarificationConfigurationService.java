package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildCategory;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web control plane for exceptional Portaria clarification tickets.
 *
 * Forms, decisions, channel creation and ticket closure are Discord-only
 * Coddy operations. The site never sees or operates the individual tickets.
 */
@Service
public class PortariaClarificationConfigurationService {
    private final GuildManagementAccessService guildAccess;
    private final CommunityDiscordRepository links;
    private final CommunityAuditLogRepository audits;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PortariaClarificationConfigurationService(
        GuildManagementAccessService guildAccess,
        CommunityDiscordRepository links,
        CommunityAuditLogRepository audits,
        UserRepository users,
        JdbcTemplate jdbc,
        ObjectMapper objectMapper
    ) {
        this.guildAccess = guildAccess;
        this.links = links;
        this.audits = audits;
        this.users = users;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record ClarificationConfig(boolean enabled, String categoryId) {}
    public record UpdateClarificationConfig(Boolean enabled, String categoryId) {}

    @Transactional(readOnly = true)
    public ClarificationConfig get(Authentication auth, String guildId) {
        AuthorizedGuild guild = guildAccess.authorize(auth, guildId);
        linkedCommunity(guild.resources().guildId());
        return read(guildId);
    }

    @Transactional
    public ClarificationConfig update(
        Authentication auth, String guildId, UpdateClarificationConfig request
    ) {
        AuthorizedGuild guild = guildAccess.authorize(auth, guildId);
        CommunityDiscord link = linkedCommunity(guild.resources().guildId());
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe enabled");
        }

        String categoryId = normalizeCategory(request.categoryId());
        if (request.enabled()) {
            if (categoryId == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Selecione a categoria privada para habilitar os tickets");
            }
            GuildResources resources = guild.resources();
            if (resources.botCapabilities() == null
                || !resources.botCapabilities().canManageChannels()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "O Coddy precisa de Manage Channels para abrir tickets");
            }
            if (resources.categories() == null
                || resources.categories().stream()
                    .filter(Objects::nonNull)
                    .map(GuildCategory::id)
                    .noneMatch(categoryId::equals)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Categoria Discord não pertence ao servidor");
            }
        }

        ClarificationConfig before = read(guildId);
        ClarificationConfig after = new ClarificationConfig(request.enabled(), categoryId);
        if (before.equals(after)) return before;

        int changed = jdbc.update("""
            UPDATE portaria_base_config
               SET clarification_tickets_enabled = ?,
                   clarification_ticket_category_id = ?
             WHERE server_guild_id = ?
            """, after.enabled() ? 1 : 0,
            categoryId == null ? null : Long.valueOf(categoryId),
            Long.parseLong(guildId));
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Configure a Portaria deste servidor antes de habilitar tickets");
        }

        User actor = users.findByEmail(auth.getName().trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Usuário não encontrado"));

        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(link.getCommunity());
        audit.setActorUser(actor);
        audit.setAction("PORTARIA_CLARIFICATION_CONFIG");
        audit.setTargetType("DISCORD_GUILD");
        audit.setTargetId(guildId);
        try {
            audit.setMetadata(objectMapper.writeValueAsString(Map.of(
                "enabled", after.enabled(),
                "categoryId", after.categoryId() == null ? "" : after.categoryId(),
                "previousEnabled", before.enabled(),
                "previousCategoryId", before.categoryId() == null ? "" : before.categoryId()
            )));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro ao registrar auditoria");
        }
        audits.save(audit);
        return after;
    }

    private CommunityDiscord linkedCommunity(String guildId) {
        long id;
        try {
            id = Long.parseLong(guildId);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado");
        }
        return links.findByGuildId(id)
            .filter(x -> Boolean.TRUE.equals(x.getActive()))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Community Discord não encontrada"));
    }

    private ClarificationConfig read(String guildId) {
        return jdbc.query("""
            SELECT clarification_tickets_enabled, clarification_ticket_category_id
              FROM portaria_base_config
             WHERE server_guild_id = ?
            """, rs -> {
                if (!rs.next()) return new ClarificationConfig(false, null);
                Object category = rs.getObject("clarification_ticket_category_id");
                return new ClarificationConfig(
                    rs.getBoolean("clarification_tickets_enabled"),
                    category == null ? null : category.toString()
                );
            }, Long.parseLong(guildId));
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String trimmed = category.trim();
        if (!trimmed.matches("[1-9]\\d*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "categoryId deve ser numérico positivo");
        }
        try {
            return Long.toString(Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId inválido");
        }
    }
}
