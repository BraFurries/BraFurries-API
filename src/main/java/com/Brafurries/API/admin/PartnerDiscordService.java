package com.Brafurries.API.admin;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Partner-discord control plane. Only the configured official BraFurries
 * Community can configure publications or call Coddy. The legacy
 * Partner.community is a linked resource, NOT a tenant discriminator.
 */
@Service
public class PartnerDiscordService {
    public record ConfigRequest(String categoryId, String threadParentChannelId) {}
    public record PartnerOptionRequest(
        Boolean exclusiveChannelEnabled, String existingTextChannelId,
        Boolean adoptExistingTextChannel
    ) {}
    public record PostRequest(String content) {}
    public record PartnerView(
        int partnerId, String name, String status, String representativeName,
        String representativeDiscordUserId, boolean exclusiveChannelEnabled,
        String existingTextChannelId, boolean adoptExistingTextChannel,
        String threadId, String textChannelId, long desiredRevision,
        long appliedRevision, String lastSyncError
    ) {}
    public record Settings(
        String guildId, String categoryId, String threadParentChannelId,
        boolean configured, List<PartnerView> partners
    ) {}

    public record OwnPublication(int partnerId, String partnerName, String guildId, String threadId) {}

    private final PlatformAuthorizationService authorization;
    private final CommunityDiscordRepository guilds;
    private final PartnerRepository partners;
    private final UserDiscordRepository identities;
    private final JdbcTemplate jdbc;
    private final PartnerDiscordClient coddy;
    private final ConcurrentHashMap<Integer, ReentrantLock> partnerLocks = new ConcurrentHashMap<>();

    public PartnerDiscordService(
        PlatformAuthorizationService authorization,
        CommunityDiscordRepository guilds,
        PartnerRepository partners,
        UserDiscordRepository identities,
        JdbcTemplate jdbc,
        PartnerDiscordClient coddy
    ) {
        this.authorization = authorization;
        this.guilds = guilds;
        this.partners = partners;
        this.identities = identities;
        this.jdbc = jdbc;
        this.coddy = coddy;
    }

    private record Context(int communityId, long guildId) {}

    private Context context() {
        int communityId = authorization.officialCommunityId();
        List<CommunityDiscord> links = guilds.findByCommunityIds(List.of(communityId));
        CommunityDiscord official = links.stream()
            .filter(link -> Boolean.TRUE.equals(link.getActive())
                && link.getCommunity().getId().equals(communityId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "Servidor Discord oficial da BraFurries não está ativo"
            ));
        return new Context(communityId, official.getGuildId());
    }

    private static Long snowflake(String raw, boolean optional) {
        if (optional && (raw == null || raw.isBlank())) return null;
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID Discord inválido");
        }
        try {
            long value = Long.parseLong(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID Discord fora do intervalo");
    }

    private static String snowflake(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> config(Context ctx) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM partner_discord_config WHERE community_id = ?", ctx.communityId()
        );
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> row = rows.getFirst();
        if (((Number) row.get("guild_id")).longValue() != ctx.guildId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configuração pertence a outro servidor");
        }
        return row;
    }

    private void requireConfigured(Context ctx) {
        if (config(ctx).isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Configure a categoria e a vitrine de parceiros primeiro"
            );
        }
    }

    private Map<Integer, Map<String, Object>> publicationRows(Context ctx) {
        Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "SELECT * FROM partner_discord_publications WHERE community_id = ?", ctx.communityId()
        )) rows.put(((Number) row.get("partner_id")).intValue(), row);
        return rows;
    }

    private Map<String, Object> publication(Context ctx, int partnerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM partner_discord_publications WHERE community_id = ? AND partner_id = ?",
            ctx.communityId(), partnerId
        );
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Partner requirePartner(int id) {
        return partners.findWithDetailsById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parceria não encontrada"));
    }

    private UserDiscord singleRepresentativeDiscord(User user) {
        if (user == null) return null;
        // A single platform user may legitimately control multiple Discord
        // accounts after identity consolidation. Never select the first one
        // arbitrarily for channel permissions.
        List<UserDiscord> links = identities.findAllByUserIdOrderByIdAsc(user.getId());
        return links.size() == 1 ? links.getFirst() : null;
    }

    private static boolean enabled(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value instanceof Number number && number.intValue() == 1
            || value instanceof Boolean flag && flag;
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        Context ctx = context();
        Map<String, Object> config = config(ctx);
        Map<Integer, Map<String, Object>> states = publicationRows(ctx);
        List<PartnerView> items = new ArrayList<>();
        for (Partner partner : partners.findAdminPartners(null, null)) {
            Map<String, Object> row = states.getOrDefault(partner.getId(), Map.of());
            User user = partner.getRepresentativeUser();
            String representativeName = user == null ? null
                : Optional.ofNullable(user.getDisplayName()).orElse(user.getUsername());
            UserDiscord discord = user == null ? null : singleRepresentativeDiscord(user);
            items.add(new PartnerView(
                partner.getId(), partner.getName(), partner.getStatus(), representativeName,
                discord == null ? null : snowflake(discord.getDiscordUserId()),
                enabled(row, "exclusive_channel_enabled"),
                snowflake(row.get("existing_text_channel_id")),
                enabled(row, "adopt_existing_text_channel"),
                snowflake(row.get("thread_id")), snowflake(row.get("text_channel_id")),
                row.isEmpty() ? 0 : ((Number) row.get("desired_revision")).longValue(),
                row.isEmpty() ? 0 : ((Number) row.get("applied_revision")).longValue(),
                (String) row.get("last_sync_error")
            ));
        }
        return new Settings(
            snowflake(ctx.guildId()), snowflake(config.get("category_id")),
            snowflake(config.get("thread_parent_channel_id")), !config.isEmpty(), List.copyOf(items)
        );
    }

    @Transactional
    public Settings updateConfig(Authentication auth, ConfigRequest request) {
        Context ctx = context();
        int actor = authorization.currentUser(auth).getId();
        Long category = snowflake(request.categoryId(), true);
        Long parent = snowflake(request.threadParentChannelId(), true);
        if (category == null && parent != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Selecione a categoria do canal de threads já existente");
        }
        Map<String, Object> prior = config(ctx);
        // Retargeting existing synced threads requires an explicit migration,
        // otherwise the Coddy could silently recreate/duplicate announcements.
        if (!prior.isEmpty()
            && (!Objects.equals(category, prior.get("category_id"))
                || !Objects.equals(parent, prior.get("thread_parent_channel_id")))
            && jdbc.queryForObject(
                "SELECT COUNT(*) FROM partner_discord_publications "
                    + "WHERE community_id = ? AND thread_id IS NOT NULL",
                Integer.class, ctx.communityId()
            ) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A vitrine já tem threads publicadas; migração de canal exige operação separada");
        }
        jdbc.update("""
            INSERT INTO partner_discord_config
                (community_id, guild_id, category_id, thread_parent_channel_id)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE category_id = VALUES(category_id),
                thread_parent_channel_id = VALUES(thread_parent_channel_id)
            """, ctx.communityId(), ctx.guildId(), category, parent);
        audit(ctx, null, actor, "CONFIG_UPDATED", "Destino da vitrine configurado");
        return settings();
    }

    @Transactional
    public PartnerView updatePartnerOption(Authentication auth, int partnerId, PartnerOptionRequest request) {
        Context ctx = context();
        requireConfigured(ctx);
        Partner partner = requirePartner(partnerId);
        int actor = authorization.currentUser(auth).getId();
        if (request.exclusiveChannelEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe se terá canal exclusivo");
        }
        Long selected = snowflake(request.existingTextChannelId(), true);
        if (selected != null && !request.exclusiveChannelEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canal existente requer opção exclusiva");
        }
        if (selected != null && !Boolean.TRUE.equals(request.adoptExistingTextChannel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme a adoção do canal existente");
        }
        if (request.exclusiveChannelEnabled()) {
            User user = partner.getRepresentativeUser();
            if (user == null || singleRepresentativeDiscord(user) == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Representante sem identidade Discord");
            }
        }
        jdbc.update("""
            INSERT INTO partner_discord_publications
                (partner_id, community_id, exclusive_channel_enabled,
                 existing_text_channel_id, adopt_existing_text_channel)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                exclusive_channel_enabled = VALUES(exclusive_channel_enabled),
                existing_text_channel_id = VALUES(existing_text_channel_id),
                adopt_existing_text_channel = VALUES(adopt_existing_text_channel),
                desired_revision = desired_revision + 1, last_sync_error = NULL
            """, partnerId, ctx.communityId(), request.exclusiveChannelEnabled() ? 1 : 0,
            selected, Boolean.TRUE.equals(request.adoptExistingTextChannel()) ? 1 : 0);
        audit(ctx, partnerId, actor, "PARTNER_OPTION_UPDATED",
            request.exclusiveChannelEnabled() ? "Canal exclusivo solicitado" : "Canal exclusivo desativado");
        return settings().partners().stream().filter(p -> p.partnerId() == partnerId).findFirst().orElseThrow();
    }

    private void audit(Context ctx, Integer partnerId, int actor, String action, String details) {
        jdbc.update(
            "INSERT INTO partner_discord_audit(community_id, partner_id, actor_user_id, action, details) "
                + "VALUES (?, ?, ?, ?, ?)",
            ctx.communityId(), partnerId, actor, action, details
        );
    }

    private static Map<String, Object> payload(
        Context ctx, Partner partner, Map<String, Object> settings, Map<String, Object> row,
        String representativeDiscordUserId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partnerId", partner.getId());
        result.put("name", partner.getName());
        result.put("description", partner.getDescription());
        result.put("links", partner.getExternalLinks().stream()
            .map(link -> Map.<String, Object>of(
                "type", link.getType() == null ? "website" : link.getType(),
                "label", link.getLabel() == null ? "" : link.getLabel(),
                "url", link.getUrl()
            )).toList());
        result.put("active", "active".equalsIgnoreCase(partner.getStatus()));
        result.put("categoryId", snowflake(settings.get("category_id")));
        result.put("categoryCreatedByBot", enabled(settings, "category_created_by_bot"));
        result.put("threadParentChannelId", snowflake(settings.get("thread_parent_channel_id")));
        result.put("threadParentCreatedByBot", enabled(settings, "thread_parent_created_by_bot"));
        result.put("threadId", snowflake(row.get("thread_id")));
        result.put("threadIntroMessageId", snowflake(row.get("thread_intro_message_id")));
        result.put("textChannelId", snowflake(row.get("text_channel_id")));
        result.put("textIntroMessageId", snowflake(row.get("text_intro_message_id")));
        result.put("textChannelCreatedByBot", enabled(row, "text_channel_created_by_bot"));
        result.put("existingTextChannelId", snowflake(row.get("existing_text_channel_id")));
        result.put("adoptExistingTextChannel", enabled(row, "adopt_existing_text_channel"));
        result.put("exclusiveChannelEnabled", enabled(row, "exclusive_channel_enabled"));
        result.put("representativeDiscordUserId", representativeDiscordUserId);
        result.put("lastRepresentativeDiscordUserId", snowflake(row.get("representative_discord_user_id")));
        return result;
    }

    public PartnerView sync(Authentication auth, int partnerId) {
        Context ctx = context();
        requireConfigured(ctx);
        int actor = authorization.currentUser(auth).getId();
        ReentrantLock lock = partnerLocks.computeIfAbsent(partnerId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Partner partner = requirePartner(partnerId);
            Map<String, Object> config = config(ctx);
            if (!"active".equalsIgnoreCase(partner.getStatus())
                && publication(ctx, partnerId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Parceria ainda não publicada; apenas parceiros ativos podem ser sincronizados");
            }
            // Create default publication state if absent; existing partners
            // remain unchanged until an explicit sync request.
            jdbc.update("""
                INSERT INTO partner_discord_publications(partner_id, community_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE partner_id = partner_id
                """, partnerId, ctx.communityId());
            Map<String, Object> current = publication(ctx, partnerId);
            if (current.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Publicação pertence a outro escopo de comunidade");
            }
            User user = partner.getRepresentativeUser();
            UserDiscord representative = user == null ? null : singleRepresentativeDiscord(user);
            String discordId = representative == null ? null : snowflake(representative.getDiscordUserId());
            Map<String, Object> request = payload(ctx, partner, config, current, discordId);
            audit(ctx, partnerId, actor, "SYNC_REQUESTED", "Sincronização solicitada");
            try {
                Map<String, Object> result = coddy.sync(snowflake(ctx.guildId()), request);
                int count = jdbc.update("""
                    UPDATE partner_discord_publications
                    SET thread_id = ?, thread_intro_message_id = ?,
                        text_channel_id = ?, text_intro_message_id = ?,
                        representative_discord_user_id = ?,
                        text_channel_created_by_bot = ?, applied_revision = desired_revision,
                        last_synced_at = NOW(6), last_sync_error = NULL
                    WHERE partner_id = ? AND community_id = ? AND desired_revision = ?
                    """,
                    snowflake(result.get("threadId")) == null ? null : snowflake(snowflake(result.get("threadId")), true),
                    snowflake(result.get("threadIntroMessageId")) == null ? null : snowflake(snowflake(result.get("threadIntroMessageId")), true),
                    snowflake(result.get("textChannelId")) == null ? null : snowflake(snowflake(result.get("textChannelId")), true),
                    snowflake(result.get("textIntroMessageId")) == null ? null : snowflake(snowflake(result.get("textIntroMessageId")), true),
                    snowflake(result.get("representativeDiscordUserId")) == null ? null : snowflake(snowflake(result.get("representativeDiscordUserId")), true),
                    Boolean.TRUE.equals(result.get("textChannelCreatedByBot")) ? 1 : 0,
                    partnerId, ctx.communityId(), ((Number) current.get("desired_revision")).longValue()
                );
                if (count != 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Configuração alterada durante a sincronização; tente novamente");
                }
                jdbc.update("""
                    UPDATE partner_discord_config
                    SET category_created_by_bot = IF(category_id IS NULL, ?, category_created_by_bot),
                        category_id = COALESCE(category_id, ?),
                        thread_parent_created_by_bot = IF(thread_parent_channel_id IS NULL, ?, thread_parent_created_by_bot),
                        thread_parent_channel_id = COALESCE(thread_parent_channel_id, ?)
                    WHERE community_id = ? AND guild_id = ?
                    """,
                    Boolean.TRUE.equals(result.get("categoryCreatedByBot")) ? 1 : 0,
                    snowflake(snowflake(result.get("categoryId")), true),
                    Boolean.TRUE.equals(result.get("threadParentCreatedByBot")) ? 1 : 0,
                    snowflake(snowflake(result.get("threadParentChannelId")), true),
                    ctx.communityId(), ctx.guildId()
                );
                audit(ctx, partnerId, actor, "SYNC_SUCCEEDED", "Recursos Discord sincronizados");
                return settings().partners().stream().filter(p -> p.partnerId() == partnerId).findFirst().orElseThrow();
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE partner_discord_publications SET last_sync_error = ? WHERE partner_id = ? AND community_id = ?",
                    failure.getMessage() == null ? "Erro desconhecido" : failure.getMessage().substring(0, Math.min(512, failure.getMessage().length())),
                    partnerId, ctx.communityId());
                audit(ctx, partnerId, actor, "SYNC_FAILED", "Falha de publicação ou reconciliação");
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    public List<PartnerView> syncAll(Authentication auth) {
        Context ctx = context();
        requireConfigured(ctx);
        List<PartnerView> results = new ArrayList<>();
        Map<Integer, Map<String, Object>> existing = publicationRows(ctx);
        for (Partner partner : partners.findAdminPartners(null, null)) {
            if (!"active".equalsIgnoreCase(partner.getStatus()) && !existing.containsKey(partner.getId())) {
                continue;
            }
            try {
                results.add(sync(auth, partner.getId()));
            } catch (RuntimeException exception) {
                // Failure remains recorded per partner; do not abort others.
            }
        }
        return results;
    }

    /**
     * Explicitly withdraw a partner from Discord before deleting its
     * administrative record. Discord history is archived/hidden, not erased.
     * The mapping is removed only after Coddy confirms all revocations.
     */
    public void unpublish(Authentication auth, int partnerId) {
        Context ctx = context();
        requireConfigured(ctx);
        int actor = authorization.currentUser(auth).getId();
        ReentrantLock lock = partnerLocks.computeIfAbsent(partnerId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Object> state = publication(ctx, partnerId);
            if (state.isEmpty()) return;
            Partner partner = requirePartner(partnerId);
            Map<String, Object> request = payload(ctx, partner, config(ctx), state, null);
            request.put("active", false);
            request.put("exclusiveChannelEnabled", false);
            coddy.sync(snowflake(ctx.guildId()), request);
            int deleted = jdbc.update(
                "DELETE FROM partner_discord_publications WHERE partner_id = ? AND community_id = ? "
                    + "AND desired_revision = ?",
                partnerId, ctx.communityId(), ((Number) state.get("desired_revision")).longValue()
            );
            if (deleted != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A parceria foi alterada durante o encerramento; revise o estado antes de tentar novamente");
            }
            audit(ctx, partnerId, actor, "UNPUBLISHED", "Divulgação Discord retirada; histórico preservado");
        } finally {
            lock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public List<OwnPublication> myPublications(Authentication auth) {
        Context ctx = context();
        User author = authorization.currentUser(auth);
        Map<Integer, Map<String, Object>> states = publicationRows(ctx);
        List<OwnPublication> result = new ArrayList<>();
        for (Partner partner : partners.findAdminPartners("active", null)) {
            if (partner.getRepresentativeUser() == null
                || !author.getId().equals(partner.getRepresentativeUser().getId())) continue;
            Map<String, Object> row = states.get(partner.getId());
            if (row == null || row.get("thread_id") == null
                || ((Number) row.get("applied_revision")).longValue() < 1) continue;
            result.add(new OwnPublication(
                partner.getId(), partner.getName(), snowflake(ctx.guildId()),
                snowflake(row.get("thread_id"))
            ));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> publish(Authentication auth, int partnerId, PostRequest request) {
        Context ctx = context();
        requireConfigured(ctx);
        User author = authorization.currentUser(auth);
        Partner partner = requirePartner(partnerId);
        if (!"active".equalsIgnoreCase(partner.getStatus())
            || partner.getRepresentativeUser() == null
            || !author.getId().equals(partner.getRepresentativeUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente o representante ativo pode publicar");
        }
        if (request.content() == null || request.content().isBlank() || request.content().length() > 1900) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem inválida ou muito longa");
        }
        Map<String, Object> current = publication(ctx, partnerId);
        if (current.isEmpty() || current.get("thread_id") == null
            || ((Number) current.get("applied_revision")).longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Thread ainda não foi publicada");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerId", partnerId);
        payload.put("threadId", snowflake(current.get("thread_id")));
        payload.put("threadIntroMessageId", snowflake(current.get("thread_intro_message_id")));
        payload.put("content", request.content().strip());
        Map<String, Object> response = coddy.publish(snowflake(ctx.guildId()), payload);
        audit(ctx, partnerId, author.getId(), "POST_PUBLISHED", "Postagem do representante");
        return response;
    }
}
