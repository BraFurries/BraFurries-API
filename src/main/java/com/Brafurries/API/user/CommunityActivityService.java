package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityActivityDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamDemand;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityActivityService {
    private static final Logger log = LoggerFactory.getLogger(CommunityActivityService.class);
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final String EMPTY_ACTION_SENTINEL = "__NO_ACTIVITY_ACTION__";

    private static final Map<String, ActionDefinition> ACTIONS = Map.ofEntries(
        action("TEAM_ROLE_CREATED", ActivityCategory.TEAM),
        action("TEAM_ROLE_UPDATED", ActivityCategory.TEAM),
        action("TEAM_ROLE_ACTIVE_CHANGED", ActivityCategory.TEAM),
        action("TEAM_ROLE_DELETED", ActivityCategory.TEAM),
        action("TEAM_DEMAND_CREATED", ActivityCategory.TEAM),
        action("TEAM_DEMAND_UPDATED", ActivityCategory.TEAM),
        action("TEAM_DEMAND_DELETED", ActivityCategory.TEAM),
        action("TEAM_MEMBER_ASSIGNED", ActivityCategory.TEAM),
        action("TEAM_MEMBER_REMOVED", ActivityCategory.TEAM),
        action("COMMUNITY_CAPABILITY_GRANTED", ActivityCategory.ACCESS),
        action("COMMUNITY_CAPABILITY_REVOKED", ActivityCategory.ACCESS),
        action("TEAM_ROLE_CAPABILITY_GRANTED", ActivityCategory.ACCESS),
        action("TEAM_ROLE_CAPABILITY_REVOKED", ActivityCategory.ACCESS),
        action("MEMBER_NOTE_CREATED", ActivityCategory.NOTES),
        action("MEMBER_NOTE_UPDATED", ActivityCategory.NOTES),
        action("MEMBER_NOTE_ARCHIVED", ActivityCategory.NOTES),
        systemAction("COMMUNITY_OWNER_SYNCHRONIZED", ActivityCategory.COMMUNITY),
        systemAction("COMMUNITY_OWNER_UNRESOLVED", ActivityCategory.COMMUNITY),
        action("XP_CONFIG_UPDATED", ActivityCategory.COMMUNITY),
        action("AI_CONFIG_UPDATED", ActivityCategory.COMMUNITY),
        action("AI_TOKEN_ROTATED", ActivityCategory.COMMUNITY),
        action("AI_TOKEN_REMOVED", ActivityCategory.COMMUNITY),
        action("VIP_CONFIG_UPDATED", ActivityCategory.COMMUNITY)
    );
    private static final List<String> KNOWN_ACTIONS = ACTIONS.keySet().stream().sorted().toList();

    private final CommunityAuthorizationService authorization;
    private final CommunityAuditLogRepository auditLogs;
    private final CommunityTeamRoleRepository roles;
    private final CommunityTeamDemandRepository demands;
    private final UserCommunityStatusRepository memberships;
    private final CommunityDiscordRepository discord;
    private final ObjectMapper objectMapper;

    public CommunityActivityService(
        CommunityAuthorizationService authorization,
        CommunityAuditLogRepository auditLogs,
        CommunityTeamRoleRepository roles,
        CommunityTeamDemandRepository demands,
        UserCommunityStatusRepository memberships,
        CommunityDiscordRepository discord,
        ObjectMapper objectMapper
    ) {
        this.authorization = authorization;
        this.auditLogs = auditLogs;
        this.roles = roles;
        this.demands = demands;
        this.memberships = memberships;
        this.discord = discord;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ActivityPage list(
        Authentication authentication,
        Integer communityId,
        String category,
        Integer actorUserId,
        LocalDateTime from,
        LocalDateTime to,
        String cursor,
        int limit
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            requireAuditAccess(authentication, communityId);
        validatePeriod(from, to);

        int normalizedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        CursorPosition position = decodeCursor(cursor);
        CategoryFilter categoryFilter = categoryFilter(category);

        List<CommunityAuditLog> found = auditLogs.findCommunityActivity(
            communityId,
            actorUserId,
            from,
            to,
            position == null ? null : position.createdAt(),
            position == null ? null : position.id(),
            categoryFilter.mode(),
            categoryFilter.actions(),
            KNOWN_ACTIONS,
            PageRequest.of(0, normalizedLimit + 1)
        );

        boolean hasMore = found.size() > normalizedLimit;
        List<CommunityAuditLog> pageRows = hasMore
            ? found.subList(0, normalizedLimit)
            : found;

        ResolutionContext resolution = resolvePage(actor.community(), pageRows);
        List<ActivityEvent> items = pageRows.stream()
            .map(row -> toEvent(row, resolution))
            .toList();

        String nextCursor = hasMore && !pageRows.isEmpty()
            ? encodeCursor(pageRows.getLast())
            : null;

        return new ActivityPage(items, nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public List<ActivityActorOption> listActors(
        Authentication authentication,
        Integer communityId
    ) {
        requireAuditAccess(authentication, communityId);
        return auditLogs.findDistinctActorsByCommunityId(communityId).stream()
            .map(user -> new ActivityActorOption(
                user.getId(),
                displayName(user),
                user.getProfileImageUrl()
            ))
            .sorted(Comparator
                .comparing(ActivityActorOption::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ActivityActorOption::userId))
            .toList();
    }

    private CommunityAuthorizationService.CommunityAccessContext requireAuditAccess(
        Authentication authentication,
        Integer communityId
    ) {
        CommunityAuthorizationService.CommunityAccessContext context =
            authorization.resolve(authentication, communityId);
        if (!context.owner() && !context.communityAdmin()) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Somente Owner ou Community Admin pode consultar a atividade"
            );
        }
        return context;
    }

    private ResolutionContext resolvePage(
        Community community,
        List<CommunityAuditLog> rows
    ) {
        Map<Long, JsonNode> metadataByAuditId = new HashMap<>();
        Set<Integer> roleIds = new LinkedHashSet<>();
        Set<Integer> demandIds = new LinkedHashSet<>();
        Set<Integer> userIds = new LinkedHashSet<>();
        boolean needsDiscord = false;

        for (CommunityAuditLog row : rows) {
            JsonNode metadata = safeMetadata(row);
            metadataByAuditId.put(row.getId(), metadata);

            switch (Objects.requireNonNullElse(row.getTargetType(), "")) {
                case "TEAM_ROLE" -> addInteger(roleIds, row.getTargetId());
                case "TEAM_DEMAND" -> addInteger(demandIds, row.getTargetId());
                case "USER" -> addInteger(userIds, row.getTargetId());
                case "DISCORD_GUILD" -> needsDiscord = true;
                default -> {
                }
            }

            ActionDefinition definition = ACTIONS.get(row.getAction());
            if (definition == null) {
                continue;
            }

            switch (row.getAction()) {
                case "TEAM_ROLE_CREATED", "TEAM_ROLE_UPDATED" ->
                    addInteger(roleIds, integerField(metadata, "parentRoleId"));
                case "TEAM_DEMAND_CREATED", "TEAM_DEMAND_UPDATED", "TEAM_DEMAND_DELETED" ->
                    roleIds.addAll(integerList(metadata, "roleIds"));
                case "TEAM_MEMBER_ASSIGNED", "TEAM_MEMBER_REMOVED" -> {
                    addInteger(roleIds, integerField(metadata, "roleId"));
                    addInteger(userIds, integerField(metadata, "userId"));
                }
                case "MEMBER_NOTE_CREATED", "MEMBER_NOTE_UPDATED", "MEMBER_NOTE_ARCHIVED" ->
                    addInteger(userIds, integerField(metadata, "memberUserId"));
                default -> {
                }
            }
        }

        Map<Integer, CommunityTeamRole> roleById = new HashMap<>();
        if (!roleIds.isEmpty()) {
            roles.findByCommunityAndIdInOrderByNameAsc(community, roleIds)
                .forEach(role -> roleById.put(role.getId(), role));
        }

        Map<Integer, CommunityTeamDemand> demandById = new HashMap<>();
        if (!demandIds.isEmpty()) {
            demands.findByCommunityAndIdInOrderByNameAsc(community, demandIds)
                .forEach(demand -> demandById.put(demand.getId(), demand));
        }

        Map<Integer, User> userById = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserCommunityStatus membership :
                memberships.findAllByCommunityIdAndUserIdIn(community.getId(), userIds)) {
                User user = membership.getUser();
                userById.putIfAbsent(user.getId(), user);
            }
        }

        CommunityDiscord discordLink = needsDiscord
            ? discord.findByCommunity(community).orElse(null)
            : null;

        return new ResolutionContext(
            metadataByAuditId,
            roleById,
            demandById,
            userById,
            discordLink
        );
    }

    private ActivityEvent toEvent(
        CommunityAuditLog row,
        ResolutionContext resolution
    ) {
        ActionDefinition definition = ACTIONS.get(row.getAction());
        ActivityCategory category = definition == null
            ? ActivityCategory.OTHER
            : definition.category();
        boolean systemGenerated = definition != null && definition.systemGenerated();

        ActivityActor actor = new ActivityActor(
            row.getActorUser().getId(),
            displayName(row.getActorUser()),
            row.getActorUser().getProfileImageUrl()
        );

        JsonNode metadata = resolution.metadataByAuditId()
            .getOrDefault(row.getId(), objectMapper.createObjectNode());

        ActivityTarget target = target(row, metadata, resolution);
        ActivityDetails details = details(row, metadata, resolution);

        return new ActivityEvent(
            row.getId(),
            row.getAction(),
            category.name(),
            row.getCreatedAt(),
            actor,
            target,
            details,
            systemGenerated
        );
    }

    private ActivityTarget target(
        CommunityAuditLog row,
        JsonNode metadata,
        ResolutionContext resolution
    ) {
        String type = row.getTargetType();
        String id = row.getTargetId();
        if (type == null || id == null) {
            return null;
        }

        String label = switch (type) {
            case "TEAM_ROLE" -> roleLabel(integerValue(id), resolution);
            case "TEAM_DEMAND" -> demandLabel(integerValue(id), resolution);
            case "USER" -> userLabel(integerValue(id), resolution);
            case "TEAM_ROLE_ASSIGNMENT" -> {
                Integer roleId = integerField(metadata, "roleId");
                Integer userId = integerField(metadata, "userId");
                yield userLabel(userId, resolution) + " · " + roleLabel(roleId, resolution);
            }
            case "MEMBER_NOTE" -> "Nota interna #" + id;
            case "DISCORD_GUILD" -> discordLabel(id, resolution);
            default -> type + " #" + id;
        };

        return new ActivityTarget(type, id, label);
    }

    private ActivityDetails details(
        CommunityAuditLog row,
        JsonNode metadata,
        ResolutionContext resolution
    ) {
        if (!ACTIONS.containsKey(row.getAction())) {
            return emptyDetails();
        }

        String capability = null;
        Boolean active = null;
        Integer roleId = null;
        String roleName = null;
        Integer userId = null;
        String userDisplayName = null;
        Integer memberUserId = null;
        String memberDisplayName = null;
        Integer parentRoleId = null;
        String parentRoleName = null;
        List<Integer> roleIds = null;
        List<String> roleNames = null;
        String source = null;

        switch (row.getAction()) {
            case "TEAM_ROLE_CREATED", "TEAM_ROLE_UPDATED" -> {
                parentRoleId = integerField(metadata, "parentRoleId");
                parentRoleName = parentRoleId == null
                    ? null
                    : roleLabel(parentRoleId, resolution);
            }
            case "TEAM_ROLE_ACTIVE_CHANGED" ->
                active = booleanField(metadata, "active");
            case "TEAM_DEMAND_CREATED", "TEAM_DEMAND_UPDATED", "TEAM_DEMAND_DELETED" -> {
                roleIds = integerList(metadata, "roleIds");
                roleNames = roleIds.stream()
                    .map(id -> roleLabel(id, resolution))
                    .toList();
            }
            case "TEAM_MEMBER_ASSIGNED", "TEAM_MEMBER_REMOVED" -> {
                roleId = integerField(metadata, "roleId");
                roleName = roleLabel(roleId, resolution);
                userId = integerField(metadata, "userId");
                userDisplayName = userLabel(userId, resolution);
            }
            case "COMMUNITY_CAPABILITY_GRANTED", "COMMUNITY_CAPABILITY_REVOKED" -> {
                capability = textField(metadata, "capability");
                userId = integerValue(row.getTargetId());
                userDisplayName = userLabel(userId, resolution);
            }
            case "TEAM_ROLE_CAPABILITY_GRANTED", "TEAM_ROLE_CAPABILITY_REVOKED" -> {
                capability = textField(metadata, "capability");
                roleId = integerValue(row.getTargetId());
                roleName = roleLabel(roleId, resolution);
            }
            case "MEMBER_NOTE_CREATED", "MEMBER_NOTE_UPDATED", "MEMBER_NOTE_ARCHIVED" -> {
                memberUserId = integerField(metadata, "memberUserId");
                memberDisplayName = userLabel(memberUserId, resolution);
            }
            case "COMMUNITY_OWNER_SYNCHRONIZED", "COMMUNITY_OWNER_UNRESOLVED" ->
                source = textField(metadata, "source");
            default -> {
            }
        }

        return new ActivityDetails(
            capability,
            active,
            roleId,
            roleName,
            userId,
            userDisplayName,
            memberUserId,
            memberDisplayName,
            parentRoleId,
            parentRoleName,
            roleIds,
            roleNames,
            source
        );
    }

    private ActivityDetails emptyDetails() {
        return new ActivityDetails(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null
        );
    }

    private String roleLabel(Integer roleId, ResolutionContext resolution) {
        if (roleId == null) {
            return "Cargo removido";
        }
        CommunityTeamRole role = resolution.roleById().get(roleId);
        return role == null ? "Cargo removido #" + roleId : role.getName();
    }

    private String demandLabel(Integer demandId, ResolutionContext resolution) {
        if (demandId == null) {
            return "Demanda removida";
        }
        CommunityTeamDemand demand = resolution.demandById().get(demandId);
        return demand == null ? "Demanda removida #" + demandId : demand.getName();
    }

    private String userLabel(Integer userId, ResolutionContext resolution) {
        if (userId == null) {
            return "Usuário";
        }
        User user = resolution.userById().get(userId);
        return user == null ? "Usuário #" + userId : displayName(user);
    }

    private String discordLabel(String guildId, ResolutionContext resolution) {
        CommunityDiscord link = resolution.discordLink();
        if (link != null && Objects.equals(String.valueOf(link.getGuildId()), guildId)) {
            return link.getName();
        }
        return "Servidor Discord #" + guildId;
    }

    private JsonNode safeMetadata(CommunityAuditLog row) {
        if (!ACTIONS.containsKey(row.getAction())
            || row.getMetadata() == null
            || row.getMetadata().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(row.getMetadata());
            return parsed != null && parsed.isObject()
                ? parsed
                : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            log.warn(
                "Ignoring malformed Community audit metadata: communityId={}, auditId={}, action={}",
                row.getCommunity().getId(),
                row.getId(),
                row.getAction()
            );
            return objectMapper.createObjectNode();
        }
    }

    private CategoryFilter categoryFilter(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return new CategoryFilter("ALL", List.of(EMPTY_ACTION_SENTINEL));
        }

        final ActivityCategory category;
        try {
            category = ActivityCategory.valueOf(
                rawCategory.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Categoria de atividade inválida"
            );
        }

        if (category == ActivityCategory.OTHER) {
            return new CategoryFilter("OTHER", List.of(EMPTY_ACTION_SENTINEL));
        }

        List<String> actions = ACTIONS.entrySet().stream()
            .filter(entry -> entry.getValue().category() == category)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        return new CategoryFilter(
            "INCLUDE",
            actions.isEmpty() ? List.of(EMPTY_ACTION_SENTINEL) : actions
        );
    }

    private void validatePeriod(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Período de atividade inválido"
            );
        }
    }

    private CursorPosition decodeCursor(String rawCursor) {
        if (rawCursor == null || rawCursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(rawCursor.trim()),
                StandardCharsets.UTF_8
            );
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException();
            }
            LocalDateTime createdAt = LocalDateTime.parse(decoded.substring(0, separator));
            long id = Long.parseLong(decoded.substring(separator + 1));
            if (id <= 0) {
                throw new IllegalArgumentException();
            }
            return new CursorPosition(createdAt, id);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cursor de atividade inválido"
            );
        }
    }

    private String encodeCursor(CommunityAuditLog row) {
        String payload = row.getCreatedAt() + "|" + row.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Integer integerField(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private Boolean booleanField(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private String textField(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private List<Integer> integerList(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.canConvertToInt()) {
                result.add(item.intValue());
            }
        });
        return List.copyOf(result);
    }

    private Integer integerValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void addInteger(Collection<Integer> values, String raw) {
        addInteger(values, integerValue(raw));
    }

    private void addInteger(Collection<Integer> values, Integer value) {
        if (value != null) {
            values.add(value);
        }
    }

    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Usuário #" + user.getId();
    }

    private static Map.Entry<String, ActionDefinition> action(
        String action,
        ActivityCategory category
    ) {
        return Map.entry(action, new ActionDefinition(category, false));
    }

    private static Map.Entry<String, ActionDefinition> systemAction(
        String action,
        ActivityCategory category
    ) {
        return Map.entry(action, new ActionDefinition(category, true));
    }

    private enum ActivityCategory {
        TEAM,
        ACCESS,
        NOTES,
        COMMUNITY,
        OTHER
    }

    private record ActionDefinition(
        ActivityCategory category,
        boolean systemGenerated
    ) {}

    private record CategoryFilter(
        String mode,
        List<String> actions
    ) {}

    private record CursorPosition(
        LocalDateTime createdAt,
        Long id
    ) {}

    private record ResolutionContext(
        Map<Long, JsonNode> metadataByAuditId,
        Map<Integer, CommunityTeamRole> roleById,
        Map<Integer, CommunityTeamDemand> demandById,
        Map<Integer, User> userById,
        CommunityDiscord discordLink
    ) {}
}
