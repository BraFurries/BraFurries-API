package com.Brafurries.API.user;

import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GuildManagementService {
    private static final Set<String> PORTARIA_TEXT_CHANNEL_TYPES = Set.of("text", "news");
    private final GuildManagementAccessService access;
    private final GuildConfigurationStore store;
    private final CoddyGuildManagementClient coddy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GuildManagementService(GuildManagementAccessService access, GuildConfigurationStore store,
                                  CoddyGuildManagementClient coddy) {
        this.access = access; this.store = store; this.coddy = coddy;
    }

    public GuildResources resources(Authentication auth, String guildId) { return access.authorize(auth, guildId).resources(); }

    public ServerChannelsConfig serverChannels(Authentication auth, String guildId) {
        access.authorize(auth, guildId);
        return store.readServerChannels(Long.parseLong(guildId));
    }

    public ServerChannelsConfig updateBirthdayChannel(Authentication auth, String guildId, BirthdayChannelRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        validateOptionalTextChannel(guild.resources(), request == null ? null : request.channelId());
        store.writeBirthdayChannel(Long.parseLong(guildId), request == null ? null : request.channelId());
        return store.readServerChannels(Long.parseLong(guildId));
    }

    public ServerChannelsConfig updateAiChannels(Authentication auth, String guildId, AiChannelsRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.channelIds() == null || request.channelLimitEnabled() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuração de canais da IA obrigatória");
        List<String> ids = request.channelIds().stream().distinct().toList();
        requirePositiveSnowflakes(ids, "channelId deve ser numérico e positivo");
        requireTextChannels(guild.resources(), ids);
        if (request.channelLimitEnabled() && ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "A limitação por canal está ativa. Selecione ao menos um canal da IA ou desative a limitação.");
        store.writeAiChannels(Long.parseLong(guildId), request.channelLimitEnabled(), ids);
        return store.readServerChannels(Long.parseLong(guildId));
    }

    public ServerChannelsConfig updateBumpProcessingChannel(Authentication auth, String guildId, BumpProcessingChannelRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        validateOptionalTextChannel(guild.resources(), request == null ? null : request.channelId());
        store.writeBumpProcessingChannel(Long.parseLong(guildId), request == null ? null : request.channelId());
        return store.readServerChannels(Long.parseLong(guildId));
    }

    public BumpConfigResponse bump(Authentication auth, String guildId) {
        access.authorize(auth, guildId);
        return bumpResponse(Long.parseLong(guildId));
    }

    public BumpConfigResponse updateBumpProcessing(Authentication auth, String guildId,
                                                    BumpProcessingChannelRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        validateOptionalTextChannel(guild.resources(), request == null ? null : request.channelId());
        store.writeBumpProcessingChannel(Long.parseLong(guildId), request == null ? null : request.channelId());
        return bumpResponse(Long.parseLong(guildId));
    }

    @Transactional
    public BumpConfigResponse updateBumpWarning(Authentication auth, String guildId,
                                                 BumpWarningRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.enabled() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuração de aviso obrigatória");
        validateOptionalTextChannel(guild.resources(), request.sourceChannelId());
        validateOptionalTextChannel(guild.resources(), request.targetChannelId());
        if (request.enabled() && request.targetChannelId() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aviso ativo exige canal de destino");
        List<String> messages = normalizeMessages(request.messages());
        long id = Long.parseLong(guildId);
        if (request.sourceChannelId() != null) store.writeBumpProcessingChannel(id, request.sourceChannelId());
        store.writeBumpWarning(id, request.enabled(), request.targetChannelId(), writeMessages(messages));
        return bumpResponse(id);
    }

    @Transactional
    public BumpConfigResponse updateBumpIndividualReward(Authentication auth, String guildId,
                                                           BumpIndividualRewardRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.role() == null || request.coins() == null
            || request.role().enabled() == null || request.coins().enabled() == null
            || request.role().durationMinutes() == null || request.coins().amount() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuração de recompensa obrigatória");
        BumpRoleRewardRequest role = request.role();
        BumpCoinRewardRequest coins = request.coins();
        if (role.durationMinutes() < 0 || coins.amount() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duração e moedas não podem ser negativas");
        if (role.message() != null && (role.message().isBlank() || role.message().length() > 1500))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem de recompensa deve ter entre 1 e 1500 caracteres");
        if (role.roleId() != null) {
            requirePositiveSnowflakes(List.of(role.roleId()), "roleId deve ser numérico e positivo");
            requireAssignableRoles(guild.resources(), List.of(role.roleId()));
        }
        if (role.enabled() && (role.roleId() == null || role.durationMinutes() <= 0))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Recompensa por cargo ativa exige cargo e duração positiva");
        if (coins.enabled() && coins.amount() <= 0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Recompensa em moedas ativa exige valor positivo");
        long id = Long.parseLong(guildId);
        boolean effectiveCoins = coins.enabled() || coins.amount() > 0;
        if (effectiveCoins && !store.hasEconomyConfig(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configuração de economia da guild não foi inicializada");
        store.writeBumpRoleReward(id, role);
        if (store.hasEconomyConfig(id)) store.writeBumpCoinReward(id, coins);
        return bumpResponse(id);
    }

    public BumpConfigResponse updateBumpMonthlyRanking(Authentication auth, String guildId,
                                                        BumpMonthlyRankingRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.enabled() == null || request.rewards() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuração de ranking mensal obrigatória");
        List<BumpMonthlyRewardTier> rewards = canonicalMonthlyRewards(request.rewards());
        validateOptionalTextChannel(guild.resources(), request.sourceChannelId());
        if (request.roleId() != null) {
            requirePositiveSnowflakes(List.of(request.roleId()), "roleId deve ser numérico e positivo");
            requireAssignableRoles(guild.resources(), List.of(request.roleId()));
        }
        boolean roleDays = rewards.stream().anyMatch(reward -> reward.roleDurationDays() > 0);
        boolean coins = rewards.stream().anyMatch(reward -> reward.coins() > 0);
        if (request.roleId() == null && roleDays)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Dias de cargo exigem um cargo mensal");
        if (request.enabled() && request.sourceChannelId() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ranking mensal ativo exige canal de origem");
        if (request.enabled() && !coins && !(request.roleId() != null && roleDays))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ranking mensal ativo exige recompensa efetiva");
        store.writeBumpMonthlyRanking(Long.parseLong(guildId), request, rewards);
        return bumpResponse(Long.parseLong(guildId));
    }

    public StaffRolesResponse staffRoles(Authentication auth, String guildId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        List<String> ids = store.readStaffRoleIds(Long.parseLong(guildId));
        return new StaffRolesResponse(ids, guild.resources().roles().stream().filter(role -> ids.contains(role.id())).toList());
    }

    public StaffRolesResponse updateStaffRoles(Authentication auth, String guildId, StaffRolesRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.roleIds() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lista de cargos staff obrigatória");
        List<String> ids = request.roleIds().stream().distinct().toList();
        if (ids.stream().anyMatch(id -> id == null || id.isBlank() || !id.matches("[1-9]\\d*")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleId deve ser numérico e positivo");
        requireRoles(guild.resources(), ids);
        store.writeStaffRoleIds(Long.parseLong(guildId), ids);
        return new StaffRolesResponse(ids, guild.resources().roles().stream().filter(role -> ids.contains(role.id())).toList());
    }

    public StaffRolesResponse vipRoles(Authentication auth, String guildId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        List<String> ids = store.readVipRoleIds(Long.parseLong(guildId));
        return new StaffRolesResponse(ids, guild.resources().roles().stream().filter(role -> ids.contains(role.id())).toList());
    }

    public StaffRolesResponse updateVipRoles(Authentication auth, String guildId, StaffRolesRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.roleIds() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lista de cargos VIP obrigatória");
        List<String> ids = request.roleIds().stream().distinct().toList();
        if (ids.stream().anyMatch(id -> id == null || !id.matches("[1-9]\\d*")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleId deve ser numérico e positivo");
        requireRoles(guild.resources(), ids);
        store.writeVipRoleIds(Long.parseLong(guildId), ids);
        return new StaffRolesResponse(ids, guild.resources().roles().stream().filter(role -> ids.contains(role.id())).toList());
    }

    public PortariaConfig updatePortariaRoles(Authentication auth, String guildId, PortariaRolesRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cargos da Portaria obrigatórios");
        requireAssignableRoles(guild.resources(), nullableValues(request.acessoProvisorioRoleId(),
            request.visitanteRoleId(), request.maior18RoleId(), request.menor18RoleId()));
        AutoJoinMutation autoJoinMutation = request.visitanteRoleId() == null
            ? AutoJoinMutation.NONE
            : ensureVisitorAutoJoin(guild, guildId, request.visitanteRoleId());
        try {
            store.writePortariaRoleIds(Long.parseLong(guildId), request);
        } catch (RuntimeException failure) {
            rollbackVisitorAutoJoin(guild, guildId, autoJoinMutation, failure);
            throw failure;
        }
        return store.readPortaria(Long.parseLong(guildId), null).config();
    }

    public PortariaResponse updatePortariaEnabled(Authentication auth, String guildId, PortariaEnabledRequest request) {
        access.authorize(auth, guildId);
        if (request == null || request.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado da Portaria obrigatório");
        }
        long parsedGuildId = Long.parseLong(guildId);
        store.writePortariaEnabled(parsedGuildId, request.enabled());
        return store.readPortaria(parsedGuildId, null);
    }

    public PortariaResponse portaria(Authentication auth, String guildId, Integer flowId) {
        access.authorize(auth, guildId);
        PortariaResponse response = store.readPortaria(Long.parseLong(guildId), flowId);
        if (flowId != null && response.selectedFlow() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fluxo não encontrado neste servidor");
        return response;
    }

    public PortariaResponse updateEntry(Authentication auth, String guildId, Integer flowId, EntryUpdateRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        requireAssignableRoles(guild.resources(), nullableValues(request.acessoProvisorioRoleId(),
            request.visitanteRoleId(), request.maior18RoleId(), request.menor18RoleId()));
        if (request.visitanteRoleId() != null && !request.visitanteRoleId().isBlank()) ensureVisitorAutoJoin(guild, guildId, request.visitanteRoleId());
        store.writeEntry(Long.parseLong(guildId), request);
        return store.readPortaria(Long.parseLong(guildId), flowId);
    }

    public PortariaResponse createFlow(Authentication auth, String guildId, CreateFlowRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        requireTextChannels(guild.resources(), List.of(request.targetChannelId()));
        int id = store.createPortariaFlow(Long.parseLong(guildId), request);
        return store.readPortaria(Long.parseLong(guildId), id);
    }

    public PortariaPublicationResult publishPortariaFlow(Authentication auth, String guildId, int flowId,
                                                          PortariaPublicationRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request == null || request.channelId() == null || !request.channelId().matches("[1-9]\\d*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId deve ser numérico e positivo");
        if (request.message() != null && request.message().length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message deve ter no máximo 2000 caracteres");
        if (request.buttonText() != null && (request.buttonText().length() > 80 || request.buttonText().trim().isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "buttonText inválido");
        long parsedGuildId = Long.parseLong(guildId);
        // Validate the persisted flow before any Discord mutation is requested.
        store.requirePortariaFlow(parsedGuildId, flowId);
        requireTextChannels(guild.resources(), List.of(request.channelId()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "portaria-publish"); payload.put("flowId", flowId); payload.put("welcomeChannelId", request.channelId());
        if (request.message() != null) payload.put("message", request.message());
        if (request.buttonText() != null) payload.put("buttonText", request.buttonText());
        JsonNode result = coddy.operation(guildId, guild.discordUserId(), payload);
        JsonNode resources = result.path("resources");
        return new PortariaPublicationResult(flowId, requiredResource(resources, "welcomeChannelId"),
            requiredResource(resources, "messageId"), containsPublication(result, "created"),
            containsPublication(result, "reused"), containsPublication(result, "updated"));
    }

    public PortariaResponse updateForm(Authentication auth, String guildId, int flowId, FormUpdateRequest request) {
        access.authorize(auth, guildId);
        store.writeForm(Long.parseLong(guildId), flowId, request);
        return store.readPortaria(Long.parseLong(guildId), flowId);
    }

    public PortariaResponse updateResult(Authentication auth, String guildId, int flowId, ResultUpdateRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (request.targetChannelId() == null || request.targetChannelId().isBlank())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Canal de fichas obrigatório");
        requireTextChannels(guild.resources(), nullableValues(request.targetChannelId(), request.approvedTargetChannelId(), request.rejectedTargetChannelId()));
        store.writeResult(Long.parseLong(guildId), flowId, request);
        return store.readPortaria(Long.parseLong(guildId), flowId);
    }

    public JsonNode preview(Authentication auth, String guildId, StructurePreviewRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        requireRoles(guild.resources(), combine(request.staffRoleIds(), request.allowedRoleIds()));
        return coddy.preview(guildId, guild.discordUserId(), request);
    }

    private static void requireRoles(GuildResources resources, List<String> ids) {
        Set<String> available = new HashSet<>(resources.roles().stream().map(GuildRole::id).toList());
        requireAvailable(ids, available, "Cargo inválido ou pertencente a outro servidor");
    }
    private static void requireAssignableRoles(GuildResources resources, List<String> ids) {
        Set<String> assignable = new HashSet<>(resources.roles().stream()
            .filter(GuildRole::editableByBot).map(GuildRole::id).toList());
        requireAvailable(ids, assignable, "Cargo operacional inválido ou não atribuível pelo Coddy");
    }
    private static void requireChannels(GuildResources resources, List<String> ids) {
        Set<String> available = new HashSet<>(resources.channels().stream().map(GuildChannel::id).toList());
        requireAvailable(ids, available, "Canal inválido ou pertencente a outro servidor");
    }
    public JsonNode operation(Authentication auth, String guildId, GuildOperationRequest request) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if ("portaria-publish".equals(request.operation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A publicação da Portaria deve usar o endpoint dedicado");
        if ("private-area".equals(request.operation())) requireRoles(guild.resources(), request.allowedRoleIds() == null ? List.of() : request.allowedRoleIds());
        if ("private-area".equals(request.operation()) && (request.allowedRoleIds() == null || request.allowedRoleIds().isEmpty()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Selecione ao menos um cargo para a Área Privada");
        if (Set.of("portaria-template", "portaria-manual", "portaria-permissions").contains(request.operation())) {
            List<String> selected = request.staffAccessRoleIds() == null ? List.of() : request.staffAccessRoleIds();
            if (selected.stream().anyMatch(id -> id == null || !id.matches("[1-9]\\d*")))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cargo de acesso da Portaria inválido");
            Set<String> globalStaff = new HashSet<>(store.readStaffRoleIds(Long.parseLong(guildId)));
            if (!globalStaff.containsAll(selected))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cargos da Portaria devem pertencer ao staff global");
            requireRoles(guild.resources(), selected);
        }
        if ("portaria-template".equals(request.operation())) return provisionTemplate(guild, guildId, request);
        if ("portaria-create-role".equals(request.operation())) return createFunctionalRole(guild, guildId, request);
        if ("portaria-create-channel".equals(request.operation())) return createFunctionalChannel(guild, guildId, request);
        if ("portaria-manual".equals(request.operation())) return provisionManual(guild, guildId, request);
        if ("portaria-repair-roles".equals(request.operation())) return repairRoles(guild, guildId, request);
        if ("portaria-repair-structure".equals(request.operation())) return repairStructure(guild, guildId, request);
        if ("portaria-permissions".equals(request.operation())) return repairPermissions(guild, guildId, request);
        if ("visitor-isolation".equals(request.operation())) {
            String visitorRoleId = store.readVisitorRoleId(Long.parseLong(guildId));
            if (visitorRoleId == null || visitorRoleId.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cargo Visitante não configurado para isolamento");
            requireRoles(guild.resources(), List.of(visitorRoleId));
            request = operation("visitor-isolation", visitorRoleId, null, null, null, null, null, null, null, List.of());
        }
        return coddy.operation(guildId, guild.discordUserId(), request);
    }

    public AutoJoinRolesResponse autoJoin(Authentication auth, String guildId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        return autoJoinResponse(guild, guildId, coddy.autoJoin(guildId, guild.discordUserId()));
    }

    public AutoJoinRolesResponse addAutoJoin(Authentication auth, String guildId, String roleId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        requireSnowflake(roleId);
        return autoJoinResponse(guild, guildId, coddy.addAutoJoin(guildId, guild.discordUserId(), roleId));
    }

    public AutoJoinRolesResponse removeAutoJoin(Authentication auth, String guildId, String roleId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        requireSnowflake(roleId);
        AutoJoinRolesConfig current = coddy.autoJoin(guildId, guild.discordUserId());
        if (portariaVisitorRole(guildId) != null && roleId.equals(portariaVisitorRole(guildId)))
            throw portariaConflict();
        return autoJoinResponse(guild, guildId, coddy.removeAutoJoin(guildId, guild.discordUserId(), roleId));
    }

    public AutoJoinRolesResponse updateAutoJoin(Authentication auth, String guildId, boolean enabled) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        if (!enabled && portariaVisitorRole(guildId) != null) throw portariaConflict();
        return autoJoinResponse(guild, guildId, coddy.updateAutoJoinEnabled(guildId, guild.discordUserId(), enabled));
    }

    private AutoJoinRolesResponse autoJoinResponse(AuthorizedGuild guild, String guildId, AutoJoinRolesConfig config) {
        String visitor = portariaVisitorRole(guildId);
        java.util.Map<String, GuildRole> roles = guild.resources().roles().stream().collect(java.util.stream.Collectors.toMap(GuildRole::id, role -> role));
        List<AutoJoinRoleResponse> responseRoles = config.roleIds().stream().map(id -> {
            String idText = String.valueOf(id); GuildRole role = roles.get(idText); boolean protectedRole = idText.equals(visitor);
            return new AutoJoinRoleResponse(idText, role == null ? null : role.name(), role == null, role == null ? null : role.position(), protectedRole, protectedRole ? "PORTARIA" : null);
        }).toList();
        List<AutoJoinWarning> warnings = new java.util.ArrayList<>();
        if (visitor != null && (!config.enabled() || !config.roleIds().contains(Long.valueOf(visitor)))) warnings.add(new AutoJoinWarning("PORTARIA_VISITOR_NOT_AUTO_JOIN", "O cargo Visitante é usado pela Portaria, mas não está sendo atribuído automaticamente aos novos membros."));
        if (visitor != null && !roles.containsKey(visitor)) warnings.add(new AutoJoinWarning("PORTARIA_VISITOR_ROLE_MISSING", "O cargo Visitante configurado pela Portaria não existe mais no Discord."));
        return new AutoJoinRolesResponse(config.enabled(), responseRoles, visitor, warnings);
    }

    private String portariaVisitorRole(String guildId) {
        PortariaResponse portaria = store.readPortaria(Long.parseLong(guildId), null);
        String visitor = portaria.config().visitanteRoleId();
        return portaria.configured() && visitor != null && !visitor.isBlank() ? visitor : null;
    }
    private static void requireSnowflake(String roleId) { if (roleId == null || !roleId.matches("[1-9]\\d*")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleId deve ser numérico e positivo"); }
    private static ResponseStatusException portariaConflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "O cargo Visitante está sendo usado pela Portaria e precisa continuar sendo atribuído automaticamente. Altere a configuração da Portaria antes de removê-lo."); }

    public DeleteFlowResult deleteFlow(Authentication auth, String guildId, int flowId) {
        AuthorizedGuild guild = access.authorize(auth, guildId);
        long parsedGuildId = Long.parseLong(guildId);
        // Validate locally first: a missing or foreign flow must never trigger a Discord mutation.
        store.requirePortariaFlow(parsedGuildId, flowId);
        List<String> warnings = new java.util.ArrayList<>();
        try {
            // A stale Discord channel/message is not part of the functional delete contract.
            coddy.operation(guildId, guild.discordUserId(), operation("portaria-delete-publications", null, null, null, null, null, null, null, flowId, List.of()));
        } catch (ResponseStatusException ex) {
            warnings.add("Formulário excluído, mas uma publicação antiga não pôde ser removida do Discord.");
        }
        int publications = store.deletePortariaFlow(parsedGuildId, flowId);
        PortariaResponse state = store.readPortaria(parsedGuildId, null);
        return new DeleteFlowResult(flowId, publications, state.configured(), warnings);
    }

    private JsonNode createFunctionalRole(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        if (!Set.of("visitor", "provisional", "minor", "adult").contains(request.rolePurpose()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Finalidade de cargo inválida");
        JsonNode result = coddy.operation(guildId, guild.discordUserId(), request);
        String roleId = requiredResource(result.path("resources"), "roleId");
        requireRoles(coddy.getResources(guildId, guild.discordUserId()), List.of(roleId));
        if ("visitor".equals(request.rolePurpose())) ensureVisitorAutoJoin(guild, guildId, roleId);
        store.writeFunctionalRole(Long.parseLong(guildId), request.rolePurpose(), roleId);
        return result;
    }

    private JsonNode createFunctionalChannel(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        if (!Set.of("entry", "target", "approved", "rejected").contains(request.channelPurpose()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Finalidade de canal inválida");
        PortariaResponse state = request.flowId() == null ? null : store.readPortaria(Long.parseLong(guildId), request.flowId());
        if (request.flowId() != null && state.selectedFlow() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fluxo Portaria não encontrado");
        JsonNode result = coddy.operation(guildId, guild.discordUserId(), request);
        String channelId = requiredResource(result.path("resources"), "channelId");
        if (state != null) {
            PortariaFlow flow = state.selectedFlow();
            String target = "target".equals(request.channelPurpose()) ? channelId : flow.targetChannelId();
            String approved = "approved".equals(request.channelPurpose()) ? channelId : flow.approvedTargetChannelId();
            String rejected = "rejected".equals(request.channelPurpose()) ? channelId : flow.rejectedTargetChannelId();
            if (!"entry".equals(request.channelPurpose())) store.writeResult(Long.parseLong(guildId), flow.id(), new ResultUpdateRequest(target, approved, rejected, flow.rejectionFeedbackEnabled()));
            else coddy.operation(guildId, guild.discordUserId(), operation("portaria-publish", null, null, null, null, null, channelId, null, flow.id(), List.of()));
        }
        return result;
    }

    private JsonNode provisionTemplate(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        JsonNode provisioned = coddy.operation(guildId, guild.discordUserId(), request);
        JsonNode resources = provisioned.path("resources");
        String visitor = requiredResource(resources, "visitanteRoleId");
        String welcome = requiredResource(resources, "welcomeChannelId");
        String target = requiredResource(resources, "targetChannelId");
        String approved = nullableResource(resources, "approvedTargetChannelId");
        String rejected = nullableResource(resources, "rejectedTargetChannelId");
        long id = Long.parseLong(guildId);
        ensureVisitorAutoJoin(guild, guildId, visitor);
        store.writeVisitorRoleId(id, visitor);
        int flowId = store.ensurePrimaryPortariaFlow(id, target, approved, rejected);
        JsonNode published = coddy.operation(guildId, guild.discordUserId(), operation("portaria-publish", null, null, null, null, null, welcome, null, flowId, List.of()));
        return combine(provisioned, published, flowId);
    }

    private JsonNode provisionManual(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        requireRoles(guild.resources(), nullableValues(request.visitorRoleId()));
        requireTextChannels(guild.resources(), nullableValues(request.welcomeChannelId(), request.targetChannelId(), request.approvedTargetChannelId(), request.rejectedTargetChannelId()));
        if (request.visitorRoleId() == null || request.targetChannelId() == null || request.welcomeChannelId() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Visitante, entrada e canal de fichas são obrigatórios");
        long id = Long.parseLong(guildId);
        ensureVisitorAutoJoin(guild, guildId, request.visitorRoleId());
        store.writeVisitorRoleId(id, request.visitorRoleId());
        int flowId = store.ensurePrimaryPortariaFlow(id, request.targetChannelId(), request.approvedTargetChannelId(), request.rejectedTargetChannelId());
        JsonNode published = coddy.operation(guildId, guild.discordUserId(), operation("portaria-publish", null, null, null, null, null, request.welcomeChannelId(), null, flowId, List.of()));
        JsonNode permissions = coddy.operation(guildId, guild.discordUserId(), operation("portaria-permissions", request.visitorRoleId(), null, null, null, null, null, null, null, portariaChannels(request)));
        return combine(published, permissions, flowId);
    }

    private JsonNode repairRoles(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        String current = store.readVisitorRoleId(Long.parseLong(guildId));
        JsonNode result = coddy.operation(guildId, guild.discordUserId(), operation("portaria-repair-roles", current, request.visitorRoleName(), null, null, null, null, null, null, List.of()));
        String visitor = requiredResource(result.path("resources"), "visitanteRoleId");
        ensureVisitorAutoJoin(guild, guildId, visitor);
        store.writeVisitorRoleId(Long.parseLong(guildId), visitor);
        return result;
    }

    private AutoJoinMutation ensureVisitorAutoJoin(AuthorizedGuild guild, String guildId, String visitorRoleId) {
        AutoJoinRolesConfig before = coddy.autoJoin(guildId, guild.discordUserId());
        long parsedVisitorRoleId = Long.parseLong(visitorRoleId);
        boolean roleAlreadyConfigured = before.roleIds() != null && before.roleIds().contains(parsedVisitorRoleId);
        boolean roleAdded = false;
        boolean enabledChanged = false;
        try {
            if (!roleAlreadyConfigured) {
                coddy.addAutoJoin(guildId, guild.discordUserId(), visitorRoleId);
                roleAdded = true;
            }
            if (!before.enabled()) {
                coddy.updateAutoJoinEnabled(guildId, guild.discordUserId(), true);
                enabledChanged = true;
            }
            return new AutoJoinMutation(visitorRoleId, roleAdded, enabledChanged);
        } catch (RuntimeException failure) {
            rollbackVisitorAutoJoin(guild, guildId, new AutoJoinMutation(visitorRoleId, roleAdded, enabledChanged), failure);
            throw failure;
        }
    }

    private void rollbackVisitorAutoJoin(AuthorizedGuild guild, String guildId, AutoJoinMutation mutation,
                                         RuntimeException failure) {
        if (mutation.enabledChanged()) {
            try { coddy.updateAutoJoinEnabled(guildId, guild.discordUserId(), false); }
            catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
        }
        if (mutation.roleAdded()) {
            try { coddy.removeAutoJoin(guildId, guild.discordUserId(), mutation.roleId()); }
            catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
        }
    }

    private record AutoJoinMutation(String roleId, boolean roleAdded, boolean enabledChanged) {
        private static final AutoJoinMutation NONE = new AutoJoinMutation(null, false, false);
    }

    private JsonNode repairStructure(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        PortariaResponse state = store.readPortaria(Long.parseLong(guildId), null);
        if (state.ambiguous()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Há mais de um fluxo Portaria; escolha um fluxo antes de reparar");
        if (state.selectedFlow() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Portaria não configurada");
        PortariaFlow flow = state.selectedFlow();
        GuildOperationRequest repair = new GuildOperationRequest("portaria-repair-structure", null, request.categoryName(), null, null, null, null, null, null,
            null, flow.targetChannelId(), flow.approvedTargetChannelId(), flow.rejectedTargetChannelId(), null, List.of(), List.of());
        JsonNode result = coddy.operation(guildId, guild.discordUserId(), repair);
        JsonNode resources = result.path("resources");
        store.writeResult(Long.parseLong(guildId), flow.id(), new ResultUpdateRequest(requiredResource(resources, "targetChannelId"), nullableResource(resources, "approvedTargetChannelId"), nullableResource(resources, "rejectedTargetChannelId"), flow.rejectionFeedbackEnabled()));
        return result;
    }

    private JsonNode repairPermissions(AuthorizedGuild guild, String guildId, GuildOperationRequest request) {
        PortariaResponse state = store.readPortaria(Long.parseLong(guildId), null);
        if (state.ambiguous()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Há mais de um fluxo Portaria; escolha um fluxo antes de reparar");
        if (state.selectedFlow() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Portaria não configurada");
        String visitor = store.readVisitorRoleId(Long.parseLong(guildId));
        if (visitor == null || visitor.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cargo Visitante não configurado");
        requireRoles(guild.resources(), List.of(visitor));
        PortariaFlow flow = state.selectedFlow();
        return coddy.operation(guildId, guild.discordUserId(), operation("portaria-permissions", visitor, null, null, null, request.staffAccessRoleIds(), null, null, null, nullableValues(flow.targetChannelId(), flow.approvedTargetChannelId(), flow.rejectedTargetChannelId())));
    }

    private static GuildOperationRequest operation(String name, String visitorRoleId, String visitorRoleName, String categoryName, List<String> allowed, List<String> staff, String welcome, String target, Integer flowId, List<String> channels) {
        return new GuildOperationRequest(name, visitorRoleName, categoryName, null, allowed, staff, null, null, visitorRoleId, welcome, target, null, null, flowId, channels, List.of());
    }
    private static List<String> portariaChannels(GuildOperationRequest request) { return nullableValues(request.welcomeChannelId(), request.targetChannelId(), request.approvedTargetChannelId(), request.rejectedTargetChannelId()); }
    private static String requiredResource(JsonNode resources, String name) { String value = nullableResource(resources, name); if (value == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coddy retornou uma operação incompleta"); return value; }
    private static String nullableResource(JsonNode resources, String name) { JsonNode value = resources.get(name); return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText(); }
    private static boolean containsPublication(JsonNode result, String name) {
        JsonNode publication = result.get(name);
        return publication != null && !publication.isNull() && (!publication.isArray() || !publication.isEmpty());
    }
    private static JsonNode combine(JsonNode first, JsonNode second, int flowId) { ObjectNode result = first.deepCopy(); result.set("publication", second); result.put("flowId", flowId); return result; }
    private static void requireTextChannels(GuildResources resources, List<String> ids) {
        Set<String> available = new HashSet<>(resources.channels().stream()
            .filter(channel -> PORTARIA_TEXT_CHANNEL_TYPES.contains(channel.type()))
            .map(GuildChannel::id).toList());
        requireAvailable(ids, available, "Canal inválido, não textual ou pertencente a outro servidor");
    }
    private BumpConfigResponse bumpResponse(long guildId) {
        GuildConfigurationStore.BumpStoredConfig stored = store.readBumpConfig(guildId);
        GuildConfigurationStore.BumpServerSettings server = stored.server();
        String effectiveProcessingChannelId = stored.processingChannelId() != null
            ? stored.processingChannelId() : server.warningSourceChannelId();
        return new BumpConfigResponse(new BumpProcessingConfig(effectiveProcessingChannelId),
            new BumpWarningConfig(server.warningEnabled(), effectiveProcessingChannelId,
                server.warningTargetChannelId(), readMessages(server.warningMessages())),
            new BumpIndividualRewardConfig(new BumpRoleRewardConfig(server.roleRewardEnabled(),
                server.roleRewardId(), server.roleRewardMinutes(), server.roleRewardMessage()),
                new BumpCoinRewardConfig(stored.coinsEnabled(), stored.coinAmount())),
            new BumpMonthlyRankingConfig(server.monthlyEnabled(), server.monthlySourceChannelId(), server.monthlyRoleId(),
                List.of(new BumpMonthlyRewardTier(1, server.monthlyDays1(), server.monthlyCoins1()),
                    new BumpMonthlyRewardTier(2, server.monthlyDays2(), server.monthlyCoins2()),
                    new BumpMonthlyRewardTier(3, server.monthlyDays3(), server.monthlyCoins3()))),
            new BumpOperationalState(server.warningNextAt(), server.warningLastBumpAt()));
    }
    private List<String> normalizeMessages(List<String> messages) {
        if (messages == null) return List.of();
        if (messages.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo de 20 mensagens de aviso");
        List<String> normalized = messages.stream().map(message -> message == null ? null : message.trim()).toList();
        if (normalized.stream().anyMatch(message -> message == null || message.isEmpty() || message.length() > 1500))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagens de aviso devem ter entre 1 e 1500 caracteres");
        return normalized;
    }
    private String writeMessages(List<String> messages) {
        if (messages.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(messages); }
        catch (Exception exception) { throw new IllegalStateException("Não foi possível serializar mensagens de aviso", exception); }
    }
    private List<String> readMessages(String persisted) {
        if (persisted == null || persisted.isBlank()) return List.of();
        try {
            List<String> messages = objectMapper.readValue(persisted, new TypeReference<List<String>>() {});
            return messages == null ? List.of() : messages;
        } catch (Exception ignored) {
            return List.of(persisted.split("\\|\\|", -1));
        }
    }
    private static List<BumpMonthlyRewardTier> canonicalMonthlyRewards(List<BumpMonthlyRewardTier> rewards) {
        if (rewards.size() != 3 || rewards.stream().anyMatch(reward -> reward == null || reward.position() < 1
            || reward.position() > 3 || reward.roleDurationDays() < 0 || reward.coins() < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ranking mensal exige três recompensas válidas");
        List<BumpMonthlyRewardTier> ordered = rewards.stream().sorted(Comparator.comparingInt(BumpMonthlyRewardTier::position)).toList();
        if (!ordered.stream().map(BumpMonthlyRewardTier::position).toList().equals(List.of(1, 2, 3)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ranking mensal exige posições 1, 2 e 3 sem duplicação");
        return ordered;
    }
    private static void validateOptionalTextChannel(GuildResources resources, String channelId) {
        if (channelId == null) return;
        requirePositiveSnowflakes(List.of(channelId), "channelId deve ser numérico e positivo");
        requireTextChannels(resources, List.of(channelId));
    }
    private static void requirePositiveSnowflakes(List<String> ids, String message) {
        if (ids.stream().anyMatch(id -> id == null || !id.matches("[1-9]\\d*")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static void requireAvailable(List<String> ids, Set<String> available, String message) {
        if (ids.stream().filter(id -> id != null && !id.isBlank()).anyMatch(id -> !id.matches("[1-9]\\d*") || !available.contains(id)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    private static List<String> combine(List<String> first, List<String> second) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        if (first != null) values.addAll(first); if (second != null) values.addAll(second); return values;
    }
    private static List<String> nullableValues(String... values) {
        return java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).toList();
    }
}
