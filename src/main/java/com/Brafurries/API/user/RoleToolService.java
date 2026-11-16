package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildChannel;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Community-scoped authorization and persistent job control plane.
 * Coddy exclusively executes Discord mutations, and updates progress.
 * The Database repository exclusively creates the two backing tables.
 */
@Service
public class RoleToolService {
    private static final Pattern SNOWFLAKE = Pattern.compile("[1-9]\\d{0,19}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final GuildManagementAccessService access;
    private final CommunityDiscordRepository links;
    private final UserRepository users;
    private final CoddyGuildManagementClient coddy;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public record BulkRequest(String roleId, String fingerprint) {}
    public record ScanRequest(String channelId, String bot) {}
    public record ApplyRequest(long scanId, String roleKey, String targetRoleId) {}
    public record RoleCandidate(String key, String name, String foundRoleId,
                                int memberCount, boolean ambiguous) {}
    public record Operation(long id, String type, String status,
                            String roleId, Long sourceOperationId, String sourceBot,
                            String channelId, String roleKey, int expectedCount,
                            int progressCurrent, int progressTotal, int addedCount,
                            int skippedCount, int failedCount, int scannedCount,
                            String errorCode, List<RoleCandidate> roles) {}

    private record Context(long guildId, int communityId, int userId, long discordUserId,
                           GuildResources resources) {}
    private record Stored(long id, long guildId, int communityId, String key, String type,
                          long actorDiscordUserId, String roleId, Long sourceId,
                          String bot, String channelId, String roleKey, String fingerprint,
                          int expectedCount, String status, String resultJson) {}

    public RoleToolService(
            GuildManagementAccessService access,
            CommunityDiscordRepository links,
            UserRepository users,
            CoddyGuildManagementClient coddy,
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper) {
        this.access = access;
        this.links = links;
        this.users = users;
        this.coddy = coddy;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.mapper = mapper;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    private static long snowflake(String value) {
        if (value == null || !SNOWFLAKE.matcher(value).matches()) throw bad("Identificador Discord inválido");
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw bad("Identificador Discord inválido");
        }
    }
    private static String operationKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw bad("Idempotency-Key obrigatório (1 a 96 caracteres)");
        }
        return key;
    }
    private Context context(Authentication auth, String guildId) {
        AuthorizedGuild authorized = access.authorize(auth, guildId);
        long id = snowflake(guildId);
        CommunityDiscord link = links.findByGuildId(id)
            .filter(row -> Boolean.TRUE.equals(row.getActive()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Servidor sem Community ativa"));
        if (link.getCommunity() == null || link.getCommunity().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Community inválida");
        }
        int actor = users.findByEmail(auth.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Conta não encontrada")).getId();
        return new Context(id, link.getCommunity().getId(), actor,
            authorized.discordUserId(), authorized.resources());
    }

    public Operation bulk(Authentication auth, String guildId, BulkRequest request, String key) {
        Context ctx = context(auth, guildId);
        operationKey(key);
        if (request == null || request.fingerprint() == null
                || !HASH.matcher(request.fingerprint()).matches()) throw bad("Prévia obrigatória");
        long roleId = snowflake(request.roleId());
        requireRole(ctx.resources(), request.roleId());
        coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());

        Stored prior = findByKey(ctx, key);
        if (prior == null) {
            JsonNode preview = coddy.bulkRolePreview(guildId, ctx.discordUserId(), request.roleId());
            if (!Objects.equals(preview.path("guildId").asText(), guildId)
                || !Objects.equals(preview.path("roleId").asText(), request.roleId())
                || !Objects.equals(preview.path("fingerprint").asText(), request.fingerprint())) {
                throw conflict("A composição do servidor mudou: refaça a prévia");
            }
            int eligible = preview.path("eligible").asInt(-1);
            if (eligible < 0) throw conflict("Prévia de membros inválida");
            prior = create(ctx, key, "BULK_ADD", roleId, null, null, null, null,
                request.fingerprint(), eligible);
        }
        assertSame(prior, ctx, "BULK_ADD", roleId, null, null, null, request.fingerprint());
        return dispatch(ctx, prior);
    }

    public Operation scan(Authentication auth, String guildId, ScanRequest request, String key) {
        Context ctx = context(auth, guildId);
        operationKey(key);
        if (request == null || request.bot() == null
                || !List.of("Dyno", "Loritta", "mee6").contains(request.bot())) {
            throw bad("Bot de origem inválido");
        }
        long channel = snowflake(request.channelId());
        boolean belongs = ctx.resources().channels().stream().anyMatch(c ->
            request.channelId().equals(c.id()) && List.of("text", "news").contains(c.type()));
        if (!belongs) throw bad("Canal inexistente ou de outra Community");
        coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());
        Stored op = findByKey(ctx, key);
        if (op == null) op = create(ctx, key, "RECOVERY_SCAN", null, null, request.bot(),
            channel, null, null, 0);
        assertSame(op, ctx, "RECOVERY_SCAN", null, null, request.bot(), channel, null);
        return dispatch(ctx, op);
    }

    public Operation apply(Authentication auth, String guildId, ApplyRequest request, String key) {
        Context ctx = context(auth, guildId);
        operationKey(key);
        if (request == null || request.scanId() <= 0 || request.roleKey() == null
                || request.roleKey().length() > 200 || request.roleKey().isBlank()) {
            throw bad("Selecione uma prévia e um cargo dos logs");
        }
        long targetRole = snowflake(request.targetRoleId());
        requireRole(ctx.resources(), request.targetRoleId());
        coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());
        Stored scan = find(ctx, request.scanId());
        if (scan == null || !"RECOVERY_SCAN".equals(scan.type())
                || !"READY".equals(scan.status()) || scan.resultJson() == null) {
            throw conflict("Análise de logs ausente, incompleta ou inválida");
        }
        int count = -1;
        try {
            JsonNode entries = mapper.readTree(scan.resultJson()).path("roleEntries");
            if (entries.isArray()) for (JsonNode entry : entries) {
                if (request.roleKey().equals(entry.path("key").asText())) {
                    count = entry.path("memberIds").size();
                    break;
                }
            }
        } catch (Exception ex) {
            throw conflict("O resultado da análise não está íntegro");
        }
        if (count < 0) throw bad("Cargo não pertence à análise selecionada");
        Stored op = findByKey(ctx, key);
        if (op == null) op = create(ctx, key, "RECOVERY_APPLY", targetRole,
            scan.id(), null, null, request.roleKey(), null, count);
        assertSame(op, ctx, "RECOVERY_APPLY", targetRole, scan.id(),
            null, null, null);
        if (!Objects.equals(op.roleKey(), request.roleKey())) {
            throw conflict("Idempotency-Key já usada para outro cargo de origem");
        }
        return dispatch(ctx, op);
    }

    private static void requireRole(GuildResources resources, String id) {
        GuildRole role = resources.roles().stream().filter(r -> id.equals(r.id())).findFirst()
            .orElseThrow(() -> bad("Cargo não pertence ao servidor selecionado"));
        if (!role.editableByBot() || role.managed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "O Coddy não pode atribuir esse cargo");
        }
    }

    private Stored create(Context ctx, String key, String type, Long roleId, Long sourceId,
                          String bot, Long channelId, String roleKey, String fingerprint,
                          int expected) {
        return Objects.requireNonNull(transactions.execute(status -> {
            int changed = jdbc.update("""
                INSERT IGNORE INTO guild_role_tool_operations
                    (community_id, guild_id, operation_key, operation_type, actor_user_id,
                     actor_discord_user_id, role_id, source_operation_id, source_bot,
                     channel_id, role_key, preview_fingerprint, expected_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, ctx.communityId(), ctx.guildId(), key, type, ctx.userId(),
                ctx.discordUserId(), roleId, sourceId, bot, channelId, roleKey,
                fingerprint, expected);
            Stored stored = findByKey(ctx, key);
            if (stored == null) throw conflict("Há outra ferramenta de cargos em execução neste servidor");
            if (changed > 0) {
                jdbc.update("""
                    INSERT INTO guild_role_tool_audit
                        (operation_id,community_id,guild_id,actor_user_id,event_type)
                    VALUES (?,?,?,?, 'REQUESTED')
                    """, stored.id(), ctx.communityId(), ctx.guildId(), ctx.userId());
            }
            return stored;
        }));
    }

    private static void assertSame(Stored op, Context ctx, String type, Long roleId,
                                   Long sourceId, String bot, Long channel, String hash) {
        if (!type.equals(op.type()) || op.actorDiscordUserId() != ctx.discordUserId()
                || !Objects.equals(roleId == null ? null : roleId.toString(), op.roleId())
                || !Objects.equals(sourceId, op.sourceId())
                || !Objects.equals(bot, op.bot())
                || !Objects.equals(channel == null ? null : channel.toString(), op.channelId())
                || !Objects.equals(hash, op.fingerprint())) {
            throw conflict("Idempotency-Key reutilizada com parâmetros diferentes");
        }
    }

    private Stored findByKey(Context ctx, String key) {
        List<Stored> rows = jdbc.query("""
            SELECT * FROM guild_role_tool_operations
            WHERE guild_id=? AND community_id=? AND operation_key=? LIMIT 1
            """, (rs, index) -> stored(rs), ctx.guildId(), ctx.communityId(), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Stored find(Context ctx, long id) {
        List<Stored> rows = jdbc.query("""
            SELECT * FROM guild_role_tool_operations
            WHERE guild_id=? AND community_id=? AND id=? LIMIT 1
            """, (rs, index) -> stored(rs), ctx.guildId(), ctx.communityId(), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        long role = rs.getLong("role_id");
        String roleId = rs.wasNull() ? null : String.valueOf(role);
        long source = rs.getLong("source_operation_id");
        Long sourceId = rs.wasNull() ? null : source;
        long channel = rs.getLong("channel_id");
        String channelId = rs.wasNull() ? null : String.valueOf(channel);
        return new Stored(rs.getLong("id"), rs.getLong("guild_id"),
            rs.getInt("community_id"), rs.getString("operation_key"),
            rs.getString("operation_type"), rs.getLong("actor_discord_user_id"),
            roleId, sourceId, rs.getString("source_bot"),
            channelId,
            rs.getString("role_key"), rs.getString("preview_fingerprint"),
            rs.getInt("expected_count"), rs.getString("status"), rs.getString("result_json"));
    }

    private Operation dispatch(Context ctx, Stored op) {
        if ("PENDING".equals(op.status())) {
            coddy.dispatchRoleTool(String.valueOf(ctx.guildId()), ctx.discordUserId(), op.id());
        }
        return getOperation(ctx, op.id());
    }

    private Operation getOperation(Context ctx, long id) {
        List<Operation> results = jdbc.query("""
            SELECT * FROM guild_role_tool_operations
            WHERE guild_id=? AND community_id=? AND id=? LIMIT 1
            """, (rs, row) -> {
                List<RoleCandidate> roles = List.of();
                if ("RECOVERY_SCAN".equals(rs.getString("operation_type"))
                        && "READY".equals(rs.getString("status"))) {
                    try {
                        JsonNode entries = mapper.readTree(rs.getString("result_json"))
                            .path("roleEntries");
                        if (entries.isArray()) {
                            java.util.ArrayList<RoleCandidate> safe = new java.util.ArrayList<>();
                            for (JsonNode entry : entries) {
                                safe.add(new RoleCandidate(entry.path("key").asText(),
                                    entry.path("name").asText(),
                                    entry.path("foundRoleId").isNull() ? null
                                        : entry.path("foundRoleId").asText(null),
                                    entry.path("memberIds").size(),
                                    entry.path("ambiguous").asBoolean(false)));
                            }
                            roles = List.copyOf(safe);
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException("Stored recovery snapshot is invalid", ex);
                    }
                }
                long role = rs.getLong("role_id");
                String roleId = rs.wasNull() ? null : String.valueOf(role);
                long source = rs.getLong("source_operation_id");
                Long sourceId = rs.wasNull() ? null : source;
                long channel = rs.getLong("channel_id");
                String channelId = rs.wasNull() ? null : String.valueOf(channel);
                return new Operation(rs.getLong("id"), rs.getString("operation_type"),
                    rs.getString("status"), roleId, sourceId, rs.getString("source_bot"),
                    channelId, rs.getString("role_key"), rs.getInt("expected_count"),
                    rs.getInt("progress_current"), rs.getInt("progress_total"),
                    rs.getInt("added_count"), rs.getInt("skipped_count"),
                    rs.getInt("failed_count"), rs.getInt("scanned_count"),
                    rs.getString("error_code"), roles);
            }, ctx.guildId(), ctx.communityId(), id);
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Operação de cargos não encontrada nesta Community");
        }
        return results.getFirst();
    }
    public Operation cancelPending(Authentication auth, String guildId, long operationId) {
        Context ctx = context(auth, guildId);
        Stored op = find(ctx, operationId);
        if (op == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operação não encontrada");
        }
        if (op.actorDiscordUserId() != ctx.discordUserId()) {
            coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());
        }
        transactions.executeWithoutResult(tx -> {
            int updated = jdbc.update("""
                UPDATE guild_role_tool_operations
                SET status='CANCELLED', error_code='CANCELLED_BY_ADMIN',
                    finished_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND guild_id=? AND community_id=? AND status='PENDING'
                """, operationId, ctx.guildId(), ctx.communityId());
            if (updated != 1) throw conflict("Somente operações ainda pendentes podem ser canceladas");
            jdbc.update("""
                INSERT INTO guild_role_tool_audit
                    (operation_id,community_id,guild_id,actor_user_id,event_type)
                VALUES (?,?,?,?, 'CANCELLED')
                """, operationId, ctx.communityId(), ctx.guildId(), ctx.userId());
        });
        return getOperation(ctx, operationId);
    }

    public Operation get(Authentication auth, String guildId, long id) {
        Context ctx = context(auth, guildId);
        // Recovery summaries can reveal sensitive past role membership.
        // A manage-guild permission alone must not expose admin role tools.
        coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());
        return getOperation(ctx, id);
    }
    public List<Operation> list(Authentication auth, String guildId) {
        Context ctx = context(auth, guildId);
        coddy.requireRoleToolAdministrator(guildId, ctx.discordUserId());
        List<Long> ids = jdbc.query("""
            SELECT id FROM guild_role_tool_operations
            WHERE guild_id=? AND community_id=? ORDER BY id DESC LIMIT 30
            """, (rs, row) -> rs.getLong(1), ctx.guildId(), ctx.communityId());
        return ids.stream().map(id -> getOperation(ctx, id)).toList();
    }
}
