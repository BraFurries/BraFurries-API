package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.XpLevelingDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.config.ConfigLevels;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.config.ConfigLevelsRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class XpLevelingService {
    private static final Logger log = LoggerFactory.getLogger(XpLevelingService.class);
    private static final List<String> LEVEL_UP_PLACEHOLDERS = List.of(
        "{user}",
        "{member}",
        "{username}",
        "{old_level}",
        "{last_level}",
        "{previous_level}",
        "{new_level}",
        "{actual_level}",
        "{level}"
    );

    private final GuildManagementAccessService access;
    private final ConfigLevelsRepository configs;
    private final CommunityDiscordRepository discordLinks;
    private final UserRepository users;
    private final CommunityAuditLogRepository auditLogs;
    private final CoddyGuildManagementClient coddy;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public XpLevelingService(
        GuildManagementAccessService access,
        ConfigLevelsRepository configs,
        CommunityDiscordRepository discordLinks,
        UserRepository users,
        CommunityAuditLogRepository auditLogs,
        CoddyGuildManagementClient coddy,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.access = access;
        this.configs = configs;
        this.discordLinks = discordLinks;
        this.users = users;
        this.auditLogs = auditLogs;
        this.coddy = coddy;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public XpConfigResponse get(Authentication authentication, String guildId) {
        access.authorize(authentication, guildId);
        Long numericGuildId = parseGuildId(guildId);
        ConfigLevels config = transactions.execute(status -> ensureConfig(numericGuildId));
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Configuração de XP indisponível");
        }
        return toResponse(config, false);
    }

    public XpSimulationResponse simulate(
        Authentication authentication,
        String guildId,
        XpSimulationRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("phase1_k", request.progression().k());
        candidate.put("phase1_p", request.progression().p());
        candidate.put("phase1_b", request.progression().b());

        JsonNode response = coddy.xpSimulation(
            guildId,
            authorized.discordUserId(),
            simulationPayload(candidate, request.levels())
        );
        return mapSimulation(response);
    }

    public XpConfigResponse update(
        Authentication authentication,
        String guildId,
        XpConfigUpdateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        Long numericGuildId = parseGuildId(guildId);

        ConfigLevels current = transactions.execute(status -> ensureConfig(numericGuildId));
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Configuração de XP indisponível");
        }
        validateUpdate(current, authorized, request);

        Map<String, Object> candidate = runtimePayload(request);
        // The runtime owns progression/policy semantics. Validate the exact
        // candidate against that implementation before committing it.
        coddy.xpSimulation(
            guildId,
            authorized.discordUserId(),
            simulationPayload(candidate, List.of(1, 5, 10, 25, 50))
        );

        User actor = authenticatedUser(authentication);
        PersistedUpdate persisted = transactions.execute(
            status -> persistUpdate(numericGuildId, actor, request)
        );
        if (persisted == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível salvar a configuração de XP");
        }

        boolean refreshPending = false;
        try {
            coddy.refreshXpRuntime(guildId, authorized.discordUserId());
        } catch (ResponseStatusException exception) {
            refreshPending = true;
            log.warn(
                "XP config saved but runtime refresh failed: guildId={}, status={}",
                guildId,
                exception.getStatusCode().value()
            );
        }

        return toResponse(persisted.config(), refreshPending);
    }

    private PersistedUpdate persistUpdate(
        Long guildId,
        User actor,
        XpConfigUpdateRequest request
    ) {
        ConfigLevels config = ensureConfig(guildId);
        List<String> changed = changedFields(config, request);
        if (changed.isEmpty()) {
            return new PersistedUpdate(config, List.of());
        }

        boolean curveChanged = changed.stream().anyMatch(
            field -> field.equals("phase1_k")
                || field.equals("phase1_p")
                || field.equals("phase1_b")
        );

        apply(config, request);
        if (curveChanged) {
            config.setLevelReconcileRequired(true);
            config.setLevelReconcileRequestedAt(LocalDateTime.now());
        }
        configs.saveAndFlush(config);

        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(config.getCommunityDiscord().getCommunity());
        audit.setActorUser(actor);
        audit.setAction("XP_CONFIG_UPDATED");
        audit.setTargetType("DISCORD_GUILD");
        audit.setTargetId(String.valueOf(guildId));
        audit.setMetadata(auditMetadata(guildId, changed));
        auditLogs.save(audit);

        return new PersistedUpdate(config, List.copyOf(changed));
    }

    private ConfigLevels ensureConfig(Long guildId) {
        CommunityDiscord link = discordLinks.findByGuildId(guildId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Servidor não vinculado a uma Community"
            ));
        configs.ensureGuildConfig(link.getGuildId());
        return configs.findByGuildId(guildId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Configuração de XP não pôde ser inicializada"
            ));
    }

    private void validateUpdate(
        ConfigLevels current,
        AuthorizedGuild authorized,
        XpConfigUpdateRequest request
    ) {
        if (request.text().baseMax() < request.text().baseMin()) {
            throw invalid("XP máximo por mensagem deve ser maior ou igual ao mínimo");
        }
        if (request.text().cooldownMaxSeconds() < request.text().cooldownMinSeconds()) {
            throw invalid("Cooldown máximo de texto deve ser maior ou igual ao mínimo");
        }
        if (request.voice().diminishingWindow2Minutes() < request.voice().diminishingWindow1Minutes()) {
            throw invalid("A segunda janela de redução de voz deve ser maior ou igual à primeira");
        }
        if (request.progression().k().compareTo(BigDecimal.ONE) < 0
            && request.progression().b().compareTo(BigDecimal.ONE) < 0) {
            throw invalid("A curva precisa ter k ou b maior ou igual a 1");
        }

        int globalCap = request.limits().globalDailyCapXp();
        int positiveSources = 0;
        if (request.limits().textDailyCapXp() > 0) {
            positiveSources += request.limits().textDailyCapXp();
        }
        if (request.limits().voiceDailyCapXp() > 0) {
            positiveSources += request.limits().voiceDailyCapXp();
        }
        if (globalCap > 0 && positiveSources > 0 && globalCap > positiveSources) {
            throw invalid("O limite global não pode exceder a soma dos limites positivos de texto e voz");
        }

        String requestedChannel = request.levelUp().channelId();
        String currentChannel = current.getLevelupWarningChannel() == null
            ? null
            : String.valueOf(current.getLevelupWarningChannel());
        boolean enablingLevelUp =
            !Boolean.TRUE.equals(current.getLevelupWarning())
                && Boolean.TRUE.equals(request.levelUp().enabled());
        boolean channelChanged = !Objects.equals(requestedChannel, currentChannel);
        if (requestedChannel != null && (enablingLevelUp || channelChanged)) {
            boolean validMessageChannel = authorized.resources().channels().stream()
                .anyMatch(channel ->
                    requestedChannel.equals(channel.id())
                        && ("text".equals(channel.type()) || "news".equals(channel.type()))
                );
            if (!validMessageChannel) {
                throw invalid("Canal de aviso de level-up inválido para esta guild");
            }
        }
    }

    private Map<String, Object> runtimePayload(XpConfigUpdateRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("text_xp_enabled", request.text().enabled());
        config.put("text_xp_base_min", request.text().baseMin());
        config.put("text_xp_base_max", request.text().baseMax());
        config.put("text_xp_cooldown_min_seconds", request.text().cooldownMinSeconds());
        config.put("text_xp_cooldown_max_seconds", request.text().cooldownMaxSeconds());
        config.put("voice_xp_enabled", request.voice().enabled());
        config.put("xp_base_per_min", request.voice().xpPerMinute());
        config.put("voice_social_bonus_pct", request.voice().socialBonusPct());
        config.put("voice_social_bonus_min_humans", request.voice().socialBonusMinHumans());
        config.put("voice_diminishing_window1_minutes", request.voice().diminishingWindow1Minutes());
        config.put("voice_diminishing_window2_minutes", request.voice().diminishingWindow2Minutes());
        config.put("voice_diminishing_factor2", request.voice().diminishingFactor2());
        config.put("voice_diminishing_factor3", request.voice().diminishingFactor3());
        config.put("phase1_k", request.progression().k());
        config.put("phase1_p", request.progression().p());
        config.put("phase1_b", request.progression().b());
        config.put("text_daily_cap_xp", request.limits().textDailyCapXp());
        config.put("voice_daily_cap_xp", request.limits().voiceDailyCapXp());
        config.put("global_daily_cap_xp", request.limits().globalDailyCapXp());
        config.put("levelup_warning", request.levelUp().enabled());
        config.put(
            "levelup_warning_channel",
            request.levelUp().channelId() == null ? null : Long.valueOf(request.levelUp().channelId())
        );
        config.put("level_up_message", request.levelUp().message());
        config.put("clear_on_exit", request.advanced().clearOnExit());
        config.put("multiplier", request.advanced().multiplier());
        return config;
    }

    private Map<String, Object> simulationPayload(
        Map<String, Object> candidate,
        List<Integer> levels
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("config", candidate);
        if (levels != null && !levels.isEmpty()) {
            payload.put("levels", levels);
        }
        return payload;
    }

    private XpSimulationResponse mapSimulation(JsonNode response) {
        String guildId = response.path("guildId").asText();
        List<XpSimulationPoint> points = new ArrayList<>();
        for (JsonNode point : response.path("points")) {
            points.add(new XpSimulationPoint(
                point.path("level").asInt(),
                point.path("totalXp").longValue(),
                point.path("xpFromPreviousLevel").longValue()
            ));
        }
        JsonNode curve = response.path("curve");
        return new XpSimulationResponse(
            guildId,
            List.copyOf(points),
            new XpProgressionConfig(
                decimalNode(curve, "phase1K"),
                decimalNode(curve, "phase1P"),
                decimalNode(curve, "phase1B")
            )
        );
    }

    private BigDecimal decimalNode(JsonNode node, String field) {
        String raw = node.path(field).asText(null);
        if (raw == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Resposta de simulação de XP inválida"
            );
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Resposta de simulação de XP inválida"
            );
        }
    }

    private void apply(ConfigLevels config, XpConfigUpdateRequest request) {
        config.setTextXpEnabled(request.text().enabled());
        config.setTextXpBaseMin(request.text().baseMin());
        config.setTextXpBaseMax(request.text().baseMax());
        config.setTextXpCooldownMinSeconds(request.text().cooldownMinSeconds());
        config.setTextXpCooldownMaxSeconds(request.text().cooldownMaxSeconds());

        config.setVoiceXpEnabled(request.voice().enabled());
        config.setXpBasePerMin(request.voice().xpPerMinute());
        config.setVoiceSocialBonusPct(request.voice().socialBonusPct());
        config.setVoiceSocialBonusMinHumans(request.voice().socialBonusMinHumans());
        config.setVoiceDiminishingWindow1Minutes(request.voice().diminishingWindow1Minutes());
        config.setVoiceDiminishingWindow2Minutes(request.voice().diminishingWindow2Minutes());
        config.setVoiceDiminishingFactor2(request.voice().diminishingFactor2());
        config.setVoiceDiminishingFactor3(request.voice().diminishingFactor3());

        config.setPhase1K(request.progression().k());
        config.setPhase1P(request.progression().p());
        config.setPhase1B(request.progression().b());

        config.setTextDailyCapXp(request.limits().textDailyCapXp());
        config.setVoiceDailyCapXp(request.limits().voiceDailyCapXp());
        config.setGlobalDailyCapXp(request.limits().globalDailyCapXp());

        config.setLevelupWarning(request.levelUp().enabled());
        config.setLevelupWarningChannel(
            request.levelUp().channelId() == null
                ? null
                : Long.valueOf(request.levelUp().channelId())
        );
        config.setLevelUpMessage(request.levelUp().message());

        config.setClearOnExit(request.advanced().clearOnExit());
        config.setMultiplier(request.advanced().multiplier());
    }

    private List<String> changedFields(ConfigLevels config, XpConfigUpdateRequest request) {
        List<String> changed = new ArrayList<>();
        changed(changed, "text_xp_enabled", config.getTextXpEnabled(), request.text().enabled());
        changed(changed, "text_xp_base_min", config.getTextXpBaseMin(), request.text().baseMin());
        changed(changed, "text_xp_base_max", config.getTextXpBaseMax(), request.text().baseMax());
        changed(changed, "text_xp_cooldown_min_seconds", config.getTextXpCooldownMinSeconds(), request.text().cooldownMinSeconds());
        changed(changed, "text_xp_cooldown_max_seconds", config.getTextXpCooldownMaxSeconds(), request.text().cooldownMaxSeconds());
        changed(changed, "voice_xp_enabled", config.getVoiceXpEnabled(), request.voice().enabled());
        changed(changed, "xp_base_per_min", config.getXpBasePerMin(), request.voice().xpPerMinute());
        changedDecimal(changed, "voice_social_bonus_pct", config.getVoiceSocialBonusPct(), request.voice().socialBonusPct());
        changed(changed, "voice_social_bonus_min_humans", config.getVoiceSocialBonusMinHumans(), request.voice().socialBonusMinHumans());
        changed(changed, "voice_diminishing_window1_minutes", config.getVoiceDiminishingWindow1Minutes(), request.voice().diminishingWindow1Minutes());
        changed(changed, "voice_diminishing_window2_minutes", config.getVoiceDiminishingWindow2Minutes(), request.voice().diminishingWindow2Minutes());
        changedDecimal(changed, "voice_diminishing_factor2", config.getVoiceDiminishingFactor2(), request.voice().diminishingFactor2());
        changedDecimal(changed, "voice_diminishing_factor3", config.getVoiceDiminishingFactor3(), request.voice().diminishingFactor3());
        changedDecimal(changed, "phase1_k", config.getPhase1K(), request.progression().k());
        changedDecimal(changed, "phase1_p", config.getPhase1P(), request.progression().p());
        changedDecimal(changed, "phase1_b", config.getPhase1B(), request.progression().b());
        changed(changed, "text_daily_cap_xp", config.getTextDailyCapXp(), request.limits().textDailyCapXp());
        changed(changed, "voice_daily_cap_xp", config.getVoiceDailyCapXp(), request.limits().voiceDailyCapXp());
        changed(changed, "global_daily_cap_xp", config.getGlobalDailyCapXp(), request.limits().globalDailyCapXp());
        changed(changed, "levelup_warning", config.getLevelupWarning(), request.levelUp().enabled());
        changed(changed, "levelup_warning_channel", config.getLevelupWarningChannel(), request.levelUp().channelId() == null ? null : Long.valueOf(request.levelUp().channelId()));
        changed(changed, "level_up_message", config.getLevelUpMessage(), request.levelUp().message());
        changed(changed, "clear_on_exit", config.getClearOnExit(), request.advanced().clearOnExit());
        changedDecimal(changed, "multiplier", normalizedMultiplier(config.getMultiplier()), request.advanced().multiplier());
        return changed;
    }

    private void changed(List<String> fields, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            fields.add(field);
        }
    }

    private void changedDecimal(
        List<String> fields,
        String field,
        BigDecimal before,
        BigDecimal after
    ) {
        if (before == null ? after != null : after == null || before.compareTo(after) != 0) {
            fields.add(field);
        }
    }

    private XpConfigResponse toResponse(ConfigLevels config, boolean refreshPending) {
        return new XpConfigResponse(
            String.valueOf(config.getCommunityDiscord().getGuildId()),
            new XpTextConfig(
                Boolean.TRUE.equals(config.getTextXpEnabled()),
                config.getTextXpBaseMin(),
                config.getTextXpBaseMax(),
                config.getTextXpCooldownMinSeconds(),
                config.getTextXpCooldownMaxSeconds()
            ),
            new XpVoiceConfig(
                Boolean.TRUE.equals(config.getVoiceXpEnabled()),
                config.getXpBasePerMin(),
                config.getVoiceSocialBonusPct(),
                config.getVoiceSocialBonusMinHumans(),
                config.getVoiceDiminishingWindow1Minutes(),
                config.getVoiceDiminishingWindow2Minutes(),
                config.getVoiceDiminishingFactor2(),
                config.getVoiceDiminishingFactor3()
            ),
            new XpProgressionConfig(
                config.getPhase1K(),
                config.getPhase1P(),
                config.getPhase1B()
            ),
            new XpLimitsConfig(
                config.getTextDailyCapXp(),
                config.getVoiceDailyCapXp(),
                config.getGlobalDailyCapXp()
            ),
            new XpLevelUpConfig(
                Boolean.TRUE.equals(config.getLevelupWarning()),
                config.getLevelupWarningChannel() == null
                    ? null
                    : String.valueOf(config.getLevelupWarningChannel()),
                config.getLevelUpMessage()
            ),
            new XpAdvancedConfig(
                Boolean.TRUE.equals(config.getClearOnExit()),
                normalizedMultiplier(config.getMultiplier()),
                config.getDailyCombo(),
                config.getComboMultiplier()
            ),
            new XpReconciliationState(
                Boolean.TRUE.equals(config.getLevelReconcileRequired()),
                config.getLevelReconcileRequestedAt(),
                config.getLevelReconciledAt()
            ),
            LEVEL_UP_PLACEHOLDERS,
            false,
            refreshPending
        );
    }

    private BigDecimal normalizedMultiplier(BigDecimal multiplier) {
        return multiplier == null ? BigDecimal.ONE : multiplier;
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        return users.findByEmail(authentication.getName().trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuário autenticado não encontrado"
            ));
    }

    private String auditMetadata(Long guildId, List<String> changed) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "guildId", String.valueOf(guildId),
                "changedFields", changed
            ));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível registrar a auditoria de XP"
            );
        }
    }

    private Long parseGuildId(String guildId) {
        try {
            return Long.valueOf(guildId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private record PersistedUpdate(
        ConfigLevels config,
        List<String> changedFields
    ) {}
}
