package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GuildConfigurationStore {
    private static final String BUMP_PROCESSING_FEATURE = "disboard_bump_processing";
    private final JdbcTemplate jdbc;

    public GuildConfigurationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> readStaffRoleIds(long guildId) {
        List<String> values = jdbc.query("SELECT staff_roles FROM config_server_settings WHERE server_guild_id = ?",
            (rs, row) -> rs.getString(1), guildId);
        if (values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) return List.of();
        return List.of(values.getFirst().split(",")).stream().map(String::trim).filter(v -> !v.isBlank()).toList();
    }
    public List<String> readVipRoleIds(long guildId) {
        List<String> values = jdbc.query("SELECT vip_roles FROM config_server_settings WHERE server_guild_id = ?",
            (rs, row) -> rs.getString(1), guildId);
        if (values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) return List.of();
        return List.of(values.getFirst().split(",")).stream().map(String::trim).filter(v -> !v.isBlank()).toList();
    }
    public String readVisitorRoleId(long guildId) {
        List<String> values = jdbc.query("SELECT visitante_role_id FROM portaria_base_config WHERE server_guild_id = ?", (rs, row) -> rs.getString(1), guildId);
        return values.isEmpty() ? null : values.getFirst();
    }

    public ServerChannelsConfig readServerChannels(long guildId) {
        ServerChannelsConfig base = jdbc.query("""
            SELECT birthday_channel_id, ai_channel_limit_enabled
            FROM config_server_settings WHERE server_guild_id=?
            """, rs -> rs.next() ? new ServerChannelsConfig(snowflake(rs.getObject(1)), List.of(), rs.getBoolean(2), null) : null, guildId);
        if (base == null) return new ServerChannelsConfig(null, List.of(), false, null);
        List<String> aiChannels = jdbc.query("""
            SELECT channel_id FROM allowed_feature_channels
            WHERE server_guild_id=? AND feature_key='ai_responses' ORDER BY channel_id
            """, (rs, row) -> snowflake(rs.getObject(1)), guildId);
        List<String> bumpChannels = jdbc.query("""
            SELECT channel_id FROM allowed_feature_channels
            WHERE server_guild_id=? AND feature_key=? ORDER BY channel_id
            """, (rs, row) -> snowflake(rs.getObject(1)), guildId, BUMP_PROCESSING_FEATURE);
        return new ServerChannelsConfig(base.birthdayChannelId(), aiChannels, base.aiChannelLimitEnabled(),
            bumpChannels.isEmpty() ? null : bumpChannels.getFirst());
    }

    @Transactional
    public void writeBirthdayChannel(long guildId, String channelId) {
        requireInitializedServerSettings(jdbc.update(
            "UPDATE config_server_settings SET birthday_channel_id=? WHERE server_guild_id=?",
            nullableLong(channelId), guildId));
    }

    @Transactional
    public void writeAiChannels(long guildId, boolean limitEnabled, List<String> channelIds) {
        requireInitializedServerSettings(jdbc.update(
            "UPDATE config_server_settings SET ai_channel_limit_enabled=? WHERE server_guild_id=?",
            limitEnabled, guildId));
        jdbc.update("DELETE FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key='ai_responses'", guildId);
        for (String channelId : channelIds) jdbc.update(
            "INSERT INTO allowed_feature_channels (server_guild_id, feature_key, channel_id) VALUES (?, 'ai_responses', ?)",
            guildId, Long.parseLong(channelId));
    }


    public AiStoredConfig readAiConfig(long guildId) {
        AiStoredConfig base = jdbc.query("""
            SELECT has_gpt_enabled,
                   gpt_model,
                   CASE
                     WHEN ai_openai_token_encrypted IS NOT NULL
                      AND TRIM(ai_openai_token_encrypted) <> '' THEN 1
                     ELSE 0
                   END AS token_configured,
                   ai_openai_token_updated_at,
                   ai_channel_limit_enabled,
                   ai_admin_channel_bypass_enabled,
                   ai_admin_user_ids
            FROM config_server_settings
            WHERE server_guild_id=?
            """, rs -> rs.next()
                ? new AiStoredConfig(
                    rs.getBoolean("has_gpt_enabled"),
                    rs.getString("gpt_model"),
                    rs.getBoolean("token_configured"),
                    localDateTime(rs.getTimestamp("ai_openai_token_updated_at")),
                    rs.getBoolean("ai_channel_limit_enabled"),
                    rs.getBoolean("ai_admin_channel_bypass_enabled"),
                    parseSnowflakeCsv(rs.getString("ai_admin_user_ids")),
                    List.of())
                : null,
            guildId
        );
        if (base == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.CONFLICT,
                "Configuração base do servidor não foi inicializada"
            );
        }

        List<String> channelIds = jdbc.query("""
            SELECT channel_id
            FROM allowed_feature_channels
            WHERE server_guild_id=? AND feature_key='ai_responses'
            ORDER BY channel_id
            """, (rs, row) -> snowflake(rs.getObject(1)), guildId);

        return new AiStoredConfig(
            base.enabled(),
            base.model(),
            base.tokenConfigured(),
            base.tokenUpdatedAt(),
            base.channelLimitEnabled(),
            base.adminChannelBypassEnabled(),
            base.adminUserIds(),
            List.copyOf(channelIds)
        );
    }

    @Transactional
    public void writeAiConfig(
        long guildId,
        boolean enabled,
        String model,
        boolean channelLimitEnabled,
        List<String> channelIds,
        boolean adminChannelBypassEnabled,
        List<String> adminUserIds
    ) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings
            SET has_gpt_enabled=?,
                gpt_model=?,
                ai_channel_limit_enabled=?,
                ai_admin_channel_bypass_enabled=?,
                ai_admin_user_ids=?
            WHERE server_guild_id=?
            """,
            enabled,
            model,
            channelLimitEnabled,
            adminChannelBypassEnabled,
            adminUserIds.isEmpty() ? null : String.join(",", adminUserIds),
            guildId
        ));

        jdbc.update(
            "DELETE FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key='ai_responses'",
            guildId
        );
        for (String channelId : channelIds) {
            jdbc.update("""
                INSERT INTO allowed_feature_channels (server_guild_id, feature_key, channel_id)
                VALUES (?, 'ai_responses', ?)
                """, guildId, Long.parseLong(channelId));
        }
    }

    public String readAiTokenCiphertext(long guildId) {
        return jdbc.query("""
            SELECT ai_openai_token_encrypted
            FROM config_server_settings
            WHERE server_guild_id=?
            """, rs -> rs.next() ? rs.getString(1) : null, guildId);
    }

    @Transactional
    public LocalDateTime writeAiTokenCredential(long guildId, String encryptedToken) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings
            SET ai_openai_token_encrypted=?,
                ai_openai_token_updated_at=CURRENT_TIMESTAMP(6)
            WHERE server_guild_id=?
            """, encryptedToken, guildId));

        LocalDateTime updatedAt = jdbc.query("""
            SELECT ai_openai_token_updated_at
            FROM config_server_settings
            WHERE server_guild_id=?
            """, rs -> rs.next() ? localDateTime(rs.getTimestamp(1)) : null, guildId);
        if (updatedAt == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Timestamp da credencial de IA não foi persistido"
            );
        }
        return updatedAt;
    }

    @Transactional
    public void removeAiTokenCredential(long guildId) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings
            SET ai_openai_token_encrypted=NULL,
                ai_openai_token_updated_at=NULL
            WHERE server_guild_id=?
            """, guildId));
    }

    private List<String> parseSnowflakeCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> value.matches("[1-9]\\d*"))
            .distinct()
            .toList();
    }

    public record AiStoredConfig(
        boolean enabled,
        String model,
        boolean tokenConfigured,
        LocalDateTime tokenUpdatedAt,
        boolean channelLimitEnabled,
        boolean adminChannelBypassEnabled,
        List<String> adminUserIds,
        List<String> channelIds
    ) {}

    private void requireInitializedServerSettings(int updated) {
        if (updated != 1) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "Configuração base do servidor não foi inicializada");
    }

    @Transactional
    public void writeBumpProcessingChannel(long guildId, String channelId) {
        jdbc.update("DELETE FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key=?",
            guildId, BUMP_PROCESSING_FEATURE);
        requireInitializedServerSettings(jdbc.update(
            "UPDATE config_server_settings SET bump_warn_disboard_channel_id=NULL WHERE server_guild_id=?",
            guildId));
        if (channelId != null && !channelId.isBlank()) jdbc.update(
            "INSERT INTO allowed_feature_channels (server_guild_id, feature_key, channel_id) VALUES (?, ?, ?)",
            guildId, BUMP_PROCESSING_FEATURE, Long.parseLong(channelId));
    }

    public BumpStoredConfig readBumpConfig(long guildId) {
        String processingChannelId = jdbc.query("SELECT channel_id FROM allowed_feature_channels WHERE server_guild_id=? AND feature_key=? ORDER BY channel_id",
            (rs, row) -> snowflake(rs.getObject(1)), guildId, BUMP_PROCESSING_FEATURE).stream().findFirst().orElse(null);
        List<BumpServerSettings> settings = jdbc.query("""
            SELECT bump_warn_enabled, bump_warn_disboard_channel_id, bump_warn_target_channel_id,
                   bump_warn_messages, bump_warn_next_at, bump_warn_last_bump_at,
                   bump_reward_enabled, bump_reward_temp_role_id, bump_reward_role_minutes, bump_reward_message,
                   bump_reward_coins, bump_monthly_enabled, bump_monthly_disboard_channel_id,
                   bump_monthly_reward_role_id, bump_monthly_reward_days_1, bump_monthly_reward_days_2,
                   bump_monthly_reward_days_3, bump_monthly_reward_coins_1, bump_monthly_reward_coins_2,
                   bump_monthly_reward_coins_3
            FROM config_server_settings WHERE server_guild_id=?
            """, (rs, row) -> new BumpServerSettings(rs.getBoolean(1), snowflake(rs.getObject(2)),
                snowflake(rs.getObject(3)), rs.getString(4), localDateTime(rs.getTimestamp(5)),
                localDateTime(rs.getTimestamp(6)), rs.getBoolean(7), snowflake(rs.getObject(8)),
                rs.getInt(9), rs.getString(10), rs.getInt(11), rs.getBoolean(12), snowflake(rs.getObject(13)),
                snowflake(rs.getObject(14)), rs.getInt(15), rs.getInt(16), rs.getInt(17), rs.getInt(18),
                rs.getInt(19), rs.getInt(20)), guildId);
        BumpServerSettings server = settings.isEmpty() ? BumpServerSettings.empty() : settings.getFirst();
        List<EconomyBumpSettings> economyRows = jdbc.query(
            "SELECT bump_reward_enabled, bump_points FROM config_economy WHERE server_guild_id=?",
            (rs, row) -> new EconomyBumpSettings(rs.getBoolean(1), (Integer) rs.getObject(2)), guildId);
        EconomyBumpSettings economy = economyRows.isEmpty() ? null : economyRows.getFirst();
        BumpCoinRewardConfig coins = resolveCoinReward(economy, server.legacyCoins());
        return new BumpStoredConfig(processingChannelId, server, coins.enabled(), coins.amount());
    }

    @Transactional
    public void writeBumpWarning(long guildId, boolean enabled, String targetChannelId, String messagesJson) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings SET bump_warn_enabled=?, bump_warn_target_channel_id=?,
              bump_warn_messages=? WHERE server_guild_id=?
            """, enabled, nullableLong(targetChannelId), messagesJson, guildId));
    }

    @Transactional
    public void writeBumpRoleReward(long guildId, BumpRoleRewardRequest request) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings SET bump_reward_enabled=?, bump_reward_temp_role_id=?,
              bump_reward_role_minutes=?, bump_reward_message=? WHERE server_guild_id=?
            """, request.enabled(), nullableLong(request.roleId()), request.durationMinutes(),
            blankToNull(request.message()), guildId));
    }

    public boolean hasEconomyConfig(long guildId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM config_economy WHERE server_guild_id=?", Integer.class, guildId);
        return count != null && count == 1;
    }

    @Transactional
    public void writeBumpCoinReward(long guildId, BumpCoinRewardRequest request) {
        int updated = jdbc.update("UPDATE config_economy SET bump_reward_enabled=?, bump_points=? WHERE server_guild_id=?",
            request.enabled(), request.amount(), guildId);
        if (updated != 1) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "Configuração de economia da guild não foi inicializada");
    }

    @Transactional
    public void writeBumpMonthlyRanking(long guildId, BumpMonthlyRankingRequest request,
                                        List<BumpMonthlyRewardTier> rewards) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings SET bump_monthly_enabled=?, bump_monthly_disboard_channel_id=?,
              bump_monthly_reward_role_id=?, bump_monthly_reward_days_1=?, bump_monthly_reward_days_2=?,
              bump_monthly_reward_days_3=?, bump_monthly_reward_coins_1=?, bump_monthly_reward_coins_2=?,
              bump_monthly_reward_coins_3=? WHERE server_guild_id=?
            """, request.enabled(), nullableLong(request.sourceChannelId()), nullableLong(request.roleId()),
            rewards.get(0).roleDurationDays(), rewards.get(1).roleDurationDays(), rewards.get(2).roleDurationDays(),
            rewards.get(0).coins(), rewards.get(1).coins(), rewards.get(2).coins(), guildId));
    }

    public record BumpStoredConfig(String processingChannelId, BumpServerSettings server,
                                   boolean coinsEnabled, Integer coinAmount) {}
    public record BumpServerSettings(boolean warningEnabled, String warningSourceChannelId,
        String warningTargetChannelId, String warningMessages, LocalDateTime warningNextAt,
        LocalDateTime warningLastBumpAt, boolean roleRewardEnabled, String roleRewardId,
        int roleRewardMinutes, String roleRewardMessage, int legacyCoins, boolean monthlyEnabled,
        String monthlySourceChannelId, String monthlyRoleId, int monthlyDays1, int monthlyDays2,
        int monthlyDays3, int monthlyCoins1, int monthlyCoins2, int monthlyCoins3) {
        static BumpServerSettings empty() { return new BumpServerSettings(false, null, null, null, null, null,
            false, null, 0, null, 0, false, null, null, 21, 14, 7, 0, 0, 0); }
    }
    static BumpCoinRewardConfig resolveCoinReward(EconomyBumpSettings economy, int legacyCoins) {
        boolean canonicalUninitialized = economy == null || (!economy.enabled() && economy.amount() == null);
        if (canonicalUninitialized && legacyCoins > 0) return new BumpCoinRewardConfig(true, legacyCoins);
        return new BumpCoinRewardConfig(economy != null && economy.enabled(), economy == null ? null : economy.amount());
    }
    record EconomyBumpSettings(boolean enabled, Integer amount) {}

    @Transactional
    public void writeVisitorRoleId(long guildId, String visitorRoleId) {
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("UPDATE portaria_base_config SET visitante_role_id=? WHERE server_guild_id=?", nullableLong(visitorRoleId), guildId);
    }
    @Transactional
    public void writeFunctionalRole(long guildId, String purpose, String roleId) {
        String column = switch (purpose) {
            case "visitor" -> "visitante_role_id"; case "provisional" -> "acesso_provisorio_role_id";
            case "minor" -> "menor_18_role_id"; case "adult" -> "maior_18_role_id";
            default -> throw new IllegalArgumentException("invalid role purpose");
        };
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("UPDATE portaria_base_config SET " + column + "=? WHERE server_guild_id=?", nullableLong(roleId), guildId);
    }
    @Transactional
    public int deletePortariaFlow(long guildId, int flowId) {
        requireOwnedFlow(guildId, flowId);
        Integer publications = jdbc.queryForObject("SELECT COUNT(*) FROM form_published_messages WHERE flow_id=?", Integer.class, flowId);
        jdbc.update("DELETE FROM form_published_messages WHERE flow_id=?", flowId);
        jdbc.update("DELETE FROM form_questions WHERE flow_id=?", flowId);
        jdbc.update("DELETE FROM form_submissions WHERE flow_id=?", flowId);
        jdbc.update("DELETE FROM form_flows WHERE id=? AND server_guild_id=?", flowId, guildId);
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM form_flows WHERE server_guild_id=? AND LOWER(type)='portaria'", Integer.class, guildId);
        if (remaining != null && remaining == 0) jdbc.update("UPDATE portaria_base_config SET formulario_portaria_ativo=FALSE WHERE server_guild_id=?", guildId);
        return publications == null ? 0 : publications;
    }

    /** Validates ownership before any best-effort remote cleanup is attempted. */
    @Transactional(readOnly = true)
    public void requirePortariaFlow(long guildId, int flowId) {
        requireOwnedFlow(guildId, flowId);
    }

    /** Reuses the sole existing Portaria flow or creates the functional primary flow once. */
    @Transactional
    public int ensurePrimaryPortariaFlow(long guildId, String targetChannelId, String approvedChannelId, String rejectedChannelId) {
        List<Integer> ids = jdbc.query("SELECT id FROM form_flows WHERE server_guild_id=? AND LOWER(type)='portaria' ORDER BY id", (rs, row) -> rs.getInt(1), guildId);
        if (ids.size() > 1) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Há mais de um fluxo Portaria; selecione e reconcilie manualmente");
        int flowId;
        if (ids.size() == 1) flowId = ids.getFirst();
        else flowId = createPortariaFlow(guildId, new CreateFlowRequest("Portaria", targetChannelId));
        writeResult(guildId, flowId, new ResultUpdateRequest(targetChannelId, approvedChannelId, rejectedChannelId, true));
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("UPDATE portaria_base_config SET formulario_portaria_ativo=TRUE WHERE server_guild_id=?", guildId);
        Integer questionCount = jdbc.queryForObject("SELECT COUNT(*) FROM form_questions WHERE flow_id=?", Integer.class, flowId);
        if (questionCount != null && questionCount == 0)
            jdbc.update("INSERT INTO form_questions (flow_id,question_text,placeholder_text,position,required) VALUES (?,?,?,?,?)", flowId, "Como devemos chamar você?", "Nome ou apelido", 1, true);
        return flowId;
    }

    @Transactional
    public void writeStaffRoleIds(long guildId, List<String> roleIds) {
        int updated = jdbc.update("UPDATE config_server_settings SET staff_roles = ? WHERE server_guild_id = ?",
            String.join(",", roleIds), guildId);
        if (updated != 1) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "Configuração base do servidor não foi inicializada");
    }
    @Transactional
    public void writeVipRoleIds(long guildId, List<String> roleIds) {
        int updated = jdbc.update("UPDATE config_server_settings SET vip_roles = ? WHERE server_guild_id = ?",
            String.join(",", roleIds), guildId);
        if (updated != 1) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "Configuração base do servidor não foi inicializada");
    }

    public VipStoredConfig readVipConfig(long guildId) {
        return jdbc.query("""
            SELECT vip_roles,
                   vip_custom_role_prefix,
                   vip_role_division_start_id,
                   vip_role_division_end_id,
                   vip_allow_staff_colors,
                   vip_reconcile_required,
                   vip_reconcile_revision
            FROM config_server_settings
            WHERE server_guild_id=?
            """, rs -> {
                if (!rs.next()) {
                    throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Configuração base do servidor não foi inicializada"
                    );
                }
                return new VipStoredConfig(
                    parseSnowflakeCsv(rs.getString("vip_roles")),
                    rs.getString("vip_custom_role_prefix"),
                    snowflake(rs.getObject("vip_role_division_start_id")),
                    snowflake(rs.getObject("vip_role_division_end_id")),
                    rs.getBoolean("vip_allow_staff_colors"),
                    rs.getBoolean("vip_reconcile_required"),
                    rs.getLong("vip_reconcile_revision")
                );
            }, guildId);
    }

    @Transactional
    public void writeVipConfig(
        long guildId,
        List<String> roleIds,
        String customRolePrefix,
        String topRoleId,
        String bottomRoleId,
        boolean allowStaffColors
    ) {
        requireInitializedServerSettings(jdbc.update("""
            UPDATE config_server_settings
            SET vip_roles=?,
                vip_custom_role_prefix=?,
                vip_role_division_start_id=?,
                vip_role_division_end_id=?,
                vip_allow_staff_colors=?,
                vip_reconcile_required=TRUE,
                vip_reconcile_revision=vip_reconcile_revision + 1
            WHERE server_guild_id=?
            """,
            roleIds.isEmpty() ? null : String.join(",", roleIds),
            customRolePrefix,
            nullableLong(topRoleId),
            nullableLong(bottomRoleId),
            allowStaffColors,
            guildId
        ));
    }

    @Transactional
    public boolean markVipReconciled(long guildId, long expectedRevision) {
        return jdbc.update("""
            UPDATE config_server_settings
            SET vip_reconcile_required=FALSE
            WHERE server_guild_id=?
              AND vip_reconcile_revision=?
            """, guildId, expectedRevision) == 1;
    }

    public record VipStoredConfig(
        List<String> roleIds,
        String customRolePrefix,
        String topRoleId,
        String bottomRoleId,
        boolean allowStaffColors,
        boolean reconcileRequired,
        long reconcileRevision
    ) {}

    @Transactional
    public void writePortariaEnabled(long guildId, boolean enabled) {
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("UPDATE portaria_base_config SET portaria_enabled=? WHERE server_guild_id=?", enabled, guildId);
    }

    @Transactional
    public void writePortariaRoleIds(long guildId, PortariaRolesRequest request) {
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("""
            UPDATE portaria_base_config SET acesso_provisorio_role_id=?, visitante_role_id=?,
              maior_18_role_id=?, menor_18_role_id=? WHERE server_guild_id=?
            """, nullableLong(request.acessoProvisorioRoleId()), nullableLong(request.visitanteRoleId()),
            nullableLong(request.maior18RoleId()), nullableLong(request.menor18RoleId()), guildId);
    }

    public PortariaResponse readPortaria(long guildId, Integer selectedFlowId) {
        PortariaConfig config = jdbc.query("""
            SELECT portaria_enabled, acesso_provisorio_role_id, visitante_role_id, maior_18_role_id, menor_18_role_id,
                   aprovacao_acesso_provisorio_ativo, aprovacao_acesso_provisorio_duracao_dias,
                   formulario_portaria_ativo, idade_minima_conta_ficha_ativa, idade_minima_conta_ficha_dias,
                   idade_minima_conta_acesso_provisorio_ativa, idade_minima_conta_acesso_provisorio_dias,
                   idade_minima_entrada_servidor_ativa, idade_minima_entrada_servidor_anos
            FROM portaria_base_config WHERE server_guild_id = ?
            """, rs -> rs.next() ? new PortariaConfig(
                rs.getBoolean(1),
                snowflake(rs.getObject(2)), snowflake(rs.getObject(3)), snowflake(rs.getObject(4)), snowflake(rs.getObject(5)),
                rs.getBoolean(6), rs.getInt(7), rs.getBoolean(8), rs.getBoolean(9), rs.getInt(10),
                rs.getBoolean(11), rs.getInt(12), rs.getBoolean(13), rs.getInt(14)) : emptyConfig(), guildId);
        List<PortariaFlow> flows = jdbc.query("""
            SELECT id, name, type, target_channel_id, approved_target_channel_id,
                   rejected_target_channel_id, rejection_feedback_enabled
            FROM form_flows WHERE server_guild_id = ? AND LOWER(type) = 'portaria' ORDER BY id
            """, (rs, row) -> new PortariaFlow(rs.getInt(1), rs.getString(2), rs.getString(3), snowflake(rs.getObject(4)),
                snowflake(rs.getObject(5)), snowflake(rs.getObject(6)), rs.getBoolean(7)), guildId);
        PortariaFlow selected = selectedFlowId == null
            ? (flows.size() == 1 ? flows.getFirst() : null)
            : flows.stream().filter(flow -> flow.id().equals(selectedFlowId)).findFirst().orElse(null);
        List<PortariaQuestion> questions = selected == null ? List.of() : jdbc.query("""
            SELECT id, question_text, placeholder_text, position, required
            FROM form_questions WHERE flow_id = ? ORDER BY position, id
            """, (rs, row) -> new PortariaQuestion(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5)), selected.id());
        return new PortariaResponse(config, flows, selected, questions, !flows.isEmpty(), flows.size() > 1 && selected == null);
    }

    @Transactional
    public void writeEntry(long guildId, EntryUpdateRequest request) {
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id = VALUES(server_guild_id)", guildId);
        jdbc.update("""
            UPDATE portaria_base_config SET acesso_provisorio_role_id=?, visitante_role_id=?, maior_18_role_id=?,
              menor_18_role_id=?, aprovacao_acesso_provisorio_ativo=?, aprovacao_acesso_provisorio_duracao_dias=?,
              idade_minima_conta_ficha_ativa=?, idade_minima_conta_ficha_dias=?,
              idade_minima_conta_acesso_provisorio_ativa=?, idade_minima_conta_acesso_provisorio_dias=?,
              idade_minima_entrada_servidor_ativa=?, idade_minima_entrada_servidor_anos=? WHERE server_guild_id=?
            """, nullableLong(request.acessoProvisorioRoleId()), nullableLong(request.visitanteRoleId()),
            nullableLong(request.maior18RoleId()), nullableLong(request.menor18RoleId()), request.aprovacaoAcessoProvisorioAtivo(),
            request.aprovacaoAcessoProvisorioDuracaoDias(), request.idadeMinimaContaFichaAtiva(), request.idadeMinimaContaFichaDias(),
            request.idadeMinimaContaAcessoProvisorioAtiva(), request.idadeMinimaContaAcessoProvisorioDias(),
            request.idadeMinimaEntradaServidorAtiva(), request.idadeMinimaEntradaServidorAnos(), guildId);
    }

    @Transactional
    public int createPortariaFlow(long guildId, CreateFlowRequest request) {
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO form_flows (server_guild_id,name,type,target_channel_id) VALUES (?,?,'portaria',?)",
                new String[] {"id"});
            statement.setLong(1, guildId); statement.setString(2, request.name().trim());
            statement.setLong(3, Long.parseLong(request.targetChannelId())); return statement;
        }, keys);
        return keys.getKey().intValue();
    }

    @Transactional
    public void writeForm(long guildId, int flowId, FormUpdateRequest request) {
        requireOwnedFlow(guildId, flowId);
        jdbc.update("INSERT INTO portaria_base_config (server_guild_id) VALUES (?) ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)", guildId);
        jdbc.update("UPDATE portaria_base_config SET formulario_portaria_ativo=? WHERE server_guild_id=?", request.formularioPortariaAtivo(), guildId);
        jdbc.update("DELETE FROM form_questions WHERE flow_id=?", flowId);
        for (int i = 0; i < request.questions().size(); i++) {
            QuestionRequest q = request.questions().get(i);
            jdbc.update("INSERT INTO form_questions (flow_id,question_text,placeholder_text,position,required) VALUES (?,?,?,?,?)",
                flowId, q.label().trim(), blankToNull(q.placeholder()), i + 1, q.required());
        }
    }

    @Transactional
    public void writeResult(long guildId, int flowId, ResultUpdateRequest request) {
        requireOwnedFlow(guildId, flowId);
        jdbc.update("UPDATE form_flows SET target_channel_id=?, approved_target_channel_id=?, rejected_target_channel_id=?, rejection_feedback_enabled=? WHERE id=? AND server_guild_id=?",
            nullableLong(request.targetChannelId()), nullableLong(request.approvedTargetChannelId()), nullableLong(request.rejectedTargetChannelId()), request.rejectionFeedbackEnabled(), flowId, guildId);
    }

    private void requireOwnedFlow(long guildId, int flowId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM form_flows WHERE id=? AND server_guild_id=? AND LOWER(type)='portaria'", Integer.class, flowId, guildId);
        if (count == null || count == 0) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "Fluxo da Portaria não encontrado neste servidor");
    }
    private static PortariaConfig emptyConfig() { return new PortariaConfig(false,null,null,null,null,true,15,true,false,0,false,30,false,0); }
    private static String snowflake(Object value) { return value == null ? null : value.toString(); }
    private static LocalDateTime localDateTime(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private static Long nullableLong(String value) { return value == null || value.isBlank() ? null : Long.valueOf(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public CollaborativeModerationStored readCollaborativeModeration(long guildId) {
        List<CollaborativeModerationStored> values = jdbc.query("""
            SELECT collab_moderation_enabled, collab_moderation_emoji,
                   collab_moderation_min_reactions
            FROM config_server_settings
            WHERE server_guild_id = ?
            """, (rs, row) -> new CollaborativeModerationStored(
                rs.getBoolean(1),
                rs.getString(2),
                Math.max(1, rs.getInt(3))
            ), guildId);
        return values.isEmpty()
            ? new CollaborativeModerationStored(false, null, 3)
            : values.getFirst();
    }

    public void writeCollaborativeModeration(long guildId, boolean enabled, String emoji, int minReactions) {
        jdbc.update(
            "INSERT INTO config_server_settings (server_guild_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE server_guild_id=VALUES(server_guild_id)",
            guildId
        );
        jdbc.update("""
            UPDATE config_server_settings
            SET collab_moderation_enabled=?,
                collab_moderation_emoji=?,
                collab_moderation_min_reactions=?
            WHERE server_guild_id=?
            """, enabled, emoji, Math.max(1, minReactions), guildId);
    }

    public boolean readPortariaEnabled(long guildId) {
        List<Boolean> values = jdbc.query(
            "SELECT portaria_enabled FROM portaria_base_config WHERE server_guild_id=?",
            (rs, row) -> rs.getBoolean(1),
            guildId
        );
        return values.isEmpty() || values.getFirst();
    }

    public void migrateLegacyAccountBypasses(long guildId) {
        jdbc.update("""
            INSERT IGNORE INTO portaria_bypasses
                (server_guild_id, bypass_type, discord_user_id, access_mode,
                 requires_form, active, created_at, updated_at)
            SELECT
                server_guild_id,
                'account',
                user_id,
                CASE WHEN LOWER(access_mode)='provisorio' THEN 'provisorio' ELSE 'completo' END,
                IF(requires_form, 1, 0),
                1,
                created_at,
                updated_at
            FROM portaria_account_release
            WHERE server_guild_id=?
            """, guildId);
    }

    public Long upsertInviteBypass(
        long guildId,
        String inviteCode,
        boolean active,
        LocalDateTime expiresAt
    ) {
        jdbc.update("""
            INSERT INTO portaria_bypasses
                (server_guild_id, bypass_type, invite_code, active, expires_at)
            VALUES (?, 'invite', ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                active=VALUES(active),
                expires_at=VALUES(expires_at),
                expired_at=NULL,
                removed_at=NULL,
                updated_at=CURRENT_TIMESTAMP
            """, guildId, inviteCode, active, expiresAt);
        return jdbc.queryForObject("""
            SELECT id FROM portaria_bypasses
            WHERE server_guild_id=? AND bypass_type='invite' AND invite_code=?
            """, Long.class, guildId, inviteCode);
    }

    public Long upsertAccountBypass(
        long guildId,
        long discordUserId,
        String accessMode,
        boolean requiresForm,
        boolean active,
        LocalDateTime expiresAt
    ) {
        jdbc.update("""
            INSERT INTO portaria_bypasses
                (server_guild_id, bypass_type, discord_user_id, access_mode,
                 requires_form, active, expires_at)
            VALUES (?, 'account', ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                access_mode=VALUES(access_mode),
                requires_form=VALUES(requires_form),
                active=VALUES(active),
                expires_at=VALUES(expires_at),
                expired_at=NULL,
                removed_at=NULL,
                updated_at=CURRENT_TIMESTAMP
            """, guildId, discordUserId, accessMode, requiresForm, active, expiresAt);
        return jdbc.queryForObject("""
            SELECT id FROM portaria_bypasses
            WHERE server_guild_id=? AND bypass_type='account' AND discord_user_id=?
            """, Long.class, guildId, discordUserId);
    }

    public PortariaBypassStored findPortariaBypass(long guildId, String type, long id) {
        List<PortariaBypassStored> values = jdbc.query("""
            SELECT id, bypass_type, discord_user_id, invite_code, access_mode,
                   requires_form, active, expires_at, expired_at, removed_at,
                   created_at, updated_at
            FROM portaria_bypasses
            WHERE id=? AND server_guild_id=? AND bypass_type=?
            """, (rs, row) -> mapPortariaBypass(rs), id, guildId, type);
        return values.isEmpty() ? null : values.getFirst();
    }

    public PortariaBypassStored findPortariaBypassByValue(long guildId, String type, String value) {
        if ("invite".equals(type)) {
            List<Long> ids = jdbc.query("""
                SELECT id FROM portaria_bypasses
                WHERE server_guild_id=? AND bypass_type='invite'
                  AND invite_code=? AND removed_at IS NULL
                """, (rs, row) -> rs.getLong(1), guildId, value);
            return ids.isEmpty() ? null : findPortariaBypass(guildId, type, ids.getFirst());
        }
        if ("account".equals(type)) {
            long discordUserId;
            try {
                discordUserId = Long.parseLong(value);
            } catch (NumberFormatException exception) {
                return null;
            }
            List<Long> ids = jdbc.query("""
                SELECT id FROM portaria_bypasses
                WHERE server_guild_id=? AND bypass_type='account'
                  AND discord_user_id=? AND removed_at IS NULL
                """, (rs, row) -> rs.getLong(1), guildId, discordUserId);
            return ids.isEmpty() ? null : findPortariaBypass(guildId, type, ids.getFirst());
        }
        return null;
    }

    public List<PortariaBypassStored> readPortariaBypasses(long guildId) {
        return jdbc.query("""
            SELECT id, bypass_type, discord_user_id, invite_code, access_mode,
                   requires_form, active, expires_at, expired_at, removed_at,
                   created_at, updated_at
            FROM portaria_bypasses
            WHERE server_guild_id=? AND removed_at IS NULL
            ORDER BY created_at DESC, bypass_type, id DESC
            """, (rs, row) -> mapPortariaBypass(rs), guildId);
    }

    public void updateInviteBypass(
        long guildId,
        long id,
        boolean active,
        LocalDateTime expiresAt
    ) {
        jdbc.update("""
            UPDATE portaria_bypasses
            SET active=?, expires_at=?, expired_at=NULL, updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND server_guild_id=? AND bypass_type='invite'
              AND removed_at IS NULL
            """, active, expiresAt, id, guildId);
    }

    public void updateAccountBypass(
        long guildId,
        long id,
        String accessMode,
        boolean requiresForm,
        boolean active,
        LocalDateTime expiresAt
    ) {
        jdbc.update("""
            UPDATE portaria_bypasses
            SET access_mode=?, requires_form=?, active=?, expires_at=?,
                expired_at=NULL, updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND server_guild_id=? AND bypass_type='account'
              AND removed_at IS NULL
            """, accessMode, requiresForm, active, expiresAt, id, guildId);
    }

    public boolean removePortariaBypass(long guildId, String type, long id) {
        int updated = jdbc.update("""
            UPDATE portaria_bypasses
            SET active=0, removed_at=UTC_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND server_guild_id=? AND bypass_type=?
              AND removed_at IS NULL
            """, id, guildId, type);
        return updated > 0;
    }

    public List<PortariaBypassStored> expireDuePortariaBypasses(long guildId) {
        List<PortariaBypassStored> due = jdbc.query("""
            SELECT id, bypass_type, discord_user_id, invite_code, access_mode,
                   requires_form, active, expires_at, expired_at, removed_at,
                   created_at, updated_at
            FROM portaria_bypasses
            WHERE server_guild_id=? AND active=1 AND removed_at IS NULL
              AND expires_at IS NOT NULL AND expires_at <= UTC_TIMESTAMP(6)
              AND expired_at IS NULL
            FOR UPDATE
            """, (rs, row) -> mapPortariaBypass(rs), guildId);

        for (PortariaBypassStored bypass : due) {
            jdbc.update("""
                UPDATE portaria_bypasses
                SET active=0, expired_at=UTC_TIMESTAMP(6), updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND server_guild_id=? AND active=1
                  AND removed_at IS NULL
                  AND expires_at IS NOT NULL
                  AND expires_at <= UTC_TIMESTAMP(6)
                  AND expired_at IS NULL
                """, bypass.id(), guildId);
        }
        return due;
    }

    private PortariaBypassStored mapPortariaBypass(java.sql.ResultSet rs) throws java.sql.SQLException {
        String type = rs.getString("bypass_type");
        Object requiresForm = rs.getObject("requires_form");
        String value = "account".equals(type)
            ? String.valueOf(rs.getLong("discord_user_id"))
            : rs.getString("invite_code");
        return new PortariaBypassStored(
            rs.getLong("id"),
            type,
            value,
            rs.getString("access_mode"),
            requiresForm == null ? null : rs.getBoolean("requires_form"),
            rs.getBoolean("active"),
            timestamp(rs.getTimestamp("expires_at")),
            timestamp(rs.getTimestamp("expired_at")),
            timestamp(rs.getTimestamp("removed_at")),
            timestamp(rs.getTimestamp("created_at")),
            timestamp(rs.getTimestamp("updated_at"))
        );
    }

    private static LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    public record CollaborativeModerationStored(boolean enabled, String emoji, int minReactions) {}

    public record PortariaBypassStored(
        Long id,
        String type,
        String value,
        String accessMode,
        Boolean requiresForm,
        boolean active,
        LocalDateTime expiresAt,
        LocalDateTime expiredAt,
        LocalDateTime removedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

}
