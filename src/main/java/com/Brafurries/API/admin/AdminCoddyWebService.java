package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyWebDtos.*;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Global ADMIN control plane; a Community-scoped guild is required for sends. */
@Service
public class AdminCoddyWebService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CommunityDiscordRepository guilds;
    private final AdminCoddyWebClient coddy;

    public AdminCoddyWebService(
        JdbcTemplate jdbc, UserRepository users,
        CommunityDiscordRepository guilds, AdminCoddyWebClient coddy
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.guilds = guilds;
        this.coddy = coddy;
    }

    private int actor(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado");
        }
        return users.findByEmail(auth.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta nao encontrada"))
            .getId();
    }

    private static long snowflake(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador Discord invalido");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador Discord invalido");
        }
    }

    private static String requestKey(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de requisicao invalido");
        }
        try {
            UUID uuid = UUID.fromString(raw);
            if (!uuid.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return uuid.toString();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de requisicao invalido");
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public PresenceView presence() {
        List<Presence> desired = jdbc.query(
            "SELECT activity_type, activity_text, availability FROM coddy_presence_settings WHERE singleton_id = 1",
            (rs, row) -> new Presence(
                rs.getString("activity_type"), rs.getString("activity_text"), rs.getString("availability")
            )
        );
        Presence current;
        try {
            current = coddy.getPresence();
        } catch (ResponseStatusException unavailable) {
            current = null;
        }
        return new PresenceView(desired.isEmpty() ? null : desired.getFirst(), current, !desired.isEmpty());
    }

    public PresenceView updatePresence(Authentication auth, PresenceUpdateRequest request) {
        int who = actor(auth);
        String text = request.activityText();
        if (text == null || text.isBlank() || !text.equals(text.trim())
            || text.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Texto de presenca invalido");
        }
        Presence desired = new Presence(request.activityType(), text, request.availability());
        String key = UUID.randomUUID().toString();

        jdbc.update("""
            INSERT INTO coddy_admin_action_audit
                (request_key, action_type, actor_user_id, outcome)
            VALUES (?, 'PRESENCE_UPDATE', ?, 'REQUESTED')
            """, key, who);
        // Persist desired state before the external effect. The Coddy READY
        // reconciler reapplies it after a process restart or temporary outage.
        jdbc.update("""
            INSERT INTO coddy_presence_settings
                (singleton_id, activity_type, activity_text, availability, updated_by_user_id)
            VALUES (1, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                activity_type = VALUES(activity_type),
                activity_text = VALUES(activity_text),
                availability = VALUES(availability),
                revision = revision + 1,
                updated_by_user_id = VALUES(updated_by_user_id)
            """, desired.activityType(), desired.activityText(), desired.availability(), who);
        try {
            Presence applied = coddy.updatePresence(desired);
            jdbc.update("UPDATE coddy_admin_action_audit SET outcome = 'APPLIED' WHERE request_key = ?", key);
            return new PresenceView(desired, applied, true);
        } catch (ResponseStatusException error) {
            jdbc.update("UPDATE coddy_admin_action_audit SET outcome = 'UNKNOWN', error_code = 'RUNTIME_UNAVAILABLE' WHERE request_key = ?", key);
            throw error;
        }
    }

    public Targets targets() {
        // A cross-Community directory is intentional only for global ADMIN;
        // resolve the exact Community owner of each live guild before display.
        var active = guilds.findAllByActiveTrueOrderByGuildIdAsc();
        var runtime = coddy.messageTargets();
        List<GuildTarget> options = runtime.guilds().stream().flatMap(g -> {
            long guildId;
            try { guildId = snowflake(g.guildId()); }
            catch (ResponseStatusException ignored) { return java.util.stream.Stream.empty(); }
            var linked = active.stream().filter(c -> Objects.equals(c.getGuildId(), guildId)).toList();
            if (linked.size() != 1 || g.channels() == null || g.channels().isEmpty()) {
                return java.util.stream.Stream.empty();
            }
            var tenant = linked.getFirst().getCommunity();
            return java.util.stream.Stream.of(new GuildTarget(
                tenant.getId(), tenant.getName(), g.guildId(), g.guildName(), g.channels()
            ));
        }).toList();
        return new Targets(options);
    }

    private void requireActiveGuild(int communityId, long guildId) {
        if (communityId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community invalida");
        // Check both tenant and exact Discord snowflake in one indexed EXISTS.
        // Never infer tenancy from a possibly non-unique guild lookup.
        if (!guilds.existsByGuildIdAndCommunityIdAndActiveTrue(guildId, communityId)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Servidor nao pertence a uma Community ativa autorizada"
            );
        }
    }

    private SendResult existing(String key, int actorId, int communityId,
                                long guildId, long channelId, String hash) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT action_type, actor_user_id, community_id, guild_id, channel_id,
                   payload_sha256, outcome, discord_message_id
            FROM coddy_admin_action_audit WHERE request_key = ?
            """, key);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        if (!"MESSAGE_SEND".equals(row.get("action_type"))
            || !(row.get("actor_user_id") instanceof Number storedActor) || storedActor.intValue() != actorId
            || !(row.get("community_id") instanceof Number storedCommunity) || storedCommunity.intValue() != communityId
            || !(row.get("guild_id") instanceof Number storedGuild) || storedGuild.longValue() != guildId
            || !(row.get("channel_id") instanceof Number storedChannel) || storedChannel.longValue() != channelId
            || !Objects.equals(row.get("payload_sha256"), hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chave utilizada por outra operacao");
        }
        if ("SENT".equals(row.get("outcome")) && row.get("discord_message_id") instanceof Number id) {
            return new SendResult(key, "sent", Long.toString(guildId),
                Long.toString(channelId), Long.toString(id.longValue()));
        }
        // Includes REQUESTED, UNKNOWN and FAILED; never replay a side effect
        // whose final Discord outcome might be unknown.
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Requisicao ja processada; confirme a entrega antes de tentar outra");
    }

    public SendResult send(Authentication auth, SendRequest request) {
        int who = actor(auth);
        if (request.communityId() == null || request.message() == null
            || request.message().isBlank() || request.message().length() > 1800) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem invalida");
        }
        long guildId = snowflake(request.guildId());
        long channelId = snowflake(request.channelId());
        int communityId = request.communityId();
        String key = requestKey(request.requestKey());
        String hash = sha256(guildId + ":" + channelId + ":" + request.message());

        SendResult already = existing(key, who, communityId, guildId, channelId, hash);
        if (already != null) return already;
        requireActiveGuild(communityId, guildId);
        boolean allowed = targets().guilds().stream()
            .anyMatch(g -> g.communityId() == communityId && g.guildId().equals(Long.toString(guildId))
                && g.channels().stream().anyMatch(c -> c.channelId().equals(Long.toString(channelId))));
        if (!allowed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal indisponivel para envio");

        try {
            jdbc.update("""
                INSERT INTO coddy_admin_action_audit (
                    request_key, action_type, actor_user_id, community_id,
                    guild_id, channel_id, payload_sha256, outcome
                ) VALUES (?, 'MESSAGE_SEND', ?, ?, ?, ?, ?, 'REQUESTED')
                """, key, who, communityId, guildId, channelId, hash);
        } catch (DuplicateKeyException conflict) {
            SendResult prior = existing(key, who, communityId, guildId, channelId, hash);
            if (prior != null) return prior;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Requisicao concorrente com resultado ainda nao conhecido");
        }
        try {
            AdminCoddyWebClient.Delivery delivered = coddy.send(guildId, channelId, request.message());
            if (!Long.toString(guildId).equals(delivered.guildId())
                || !Long.toString(channelId).equals(delivered.channelId())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Resposta Coddy inconsistente");
            }
            long messageId = snowflake(delivered.messageId());
            jdbc.update("""
                UPDATE coddy_admin_action_audit
                SET outcome = 'SENT', discord_message_id = ?
                WHERE request_key = ? AND outcome = 'REQUESTED'
                """, messageId, key);
            return new SendResult(key, "sent", Long.toString(guildId),
                Long.toString(channelId), Long.toString(messageId));
        } catch (ResponseStatusException error) {
            jdbc.update("""
                UPDATE coddy_admin_action_audit SET outcome = 'UNKNOWN',
                    error_code = 'DELIVERY_UNCERTAIN'
                WHERE request_key = ? AND outcome = 'REQUESTED'
                """, key);
            throw error;
        }
    }
}
