package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityAccessDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapability;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrant;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleCapabilityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.community.CommunityUserCapabilityGrantRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(CommunityAuthorizationService.class);

    private final CommunityRepository communityRepository;
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityTeamRoleAssignmentRepository assignmentRepository;
    private final CommunityTeamRoleCapabilityRepository roleCapabilityRepository;
    private final CommunityUserCapabilityGrantRepository directGrantRepository;
    private final CommunityNetworkAvailabilityService networkAvailability;
    private final CommunityMembershipStatusResolver membershipStatusResolver;

    public CommunityAuthorizationService(
        CommunityRepository communityRepository,
        UserRepository userRepository,
        UserCommunityStatusRepository membershipRepository,
        CommunityTeamRoleRepository roleRepository,
        CommunityTeamRoleAssignmentRepository assignmentRepository,
        CommunityTeamRoleCapabilityRepository roleCapabilityRepository,
        CommunityUserCapabilityGrantRepository directGrantRepository,
        CommunityNetworkAvailabilityService networkAvailability,
        CommunityMembershipStatusResolver membershipStatusResolver
    ) {
        this.communityRepository = communityRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.roleCapabilityRepository = roleCapabilityRepository;
        this.directGrantRepository = directGrantRepository;
        this.networkAvailability = networkAvailability;
        this.membershipStatusResolver = membershipStatusResolver;
    }

    @Transactional(readOnly = true)
    public CommunityAccessResponse getAccess(Authentication authentication, Integer communityId) {
        return toResponse(resolve(authentication, communityId));
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext resolve(Authentication authentication, Integer communityId) {
        User user = authenticatedUser(authentication);
        Community community = communityRepository.findById(communityId)
            .orElseThrow(this::communityNotFound);
        boolean portariaEnabled = networkAvailability.isPortariaEnabled(community);

        boolean owner = community.getOwnerUser() != null
            && Objects.equals(community.getOwnerUser().getId(), user.getId());

        UserCommunityStatus membership = membershipRepository.findByUserAndCommunity(user, community)
            .orElse(null);
        boolean activeMember = isAuthorizationEligibleMembership(membership, portariaEnabled);

        if (!owner && !activeMember) {
            throw communityNotFound();
        }

        List<CommunityTeamRole> allRoles = roleRepository.findByCommunityOrderByNameAsc(community);
        Map<Integer, CommunityTeamRole> rolesById = allRoles.stream()
            .collect(Collectors.toMap(CommunityTeamRole::getId, role -> role));

        List<CommunityTeamRoleAssignment> assignments = activeMember
            ? assignmentRepository.findByCommunityIdAndUserIdOrderByRoleIdAsc(communityId, user.getId())
            : List.of();

        LinkedHashSet<Integer> activeRoleIds = assignments.stream()
            .map(CommunityTeamRoleAssignment::getRoleId)
            .filter(roleId -> {
                CommunityTeamRole role = rolesById.get(roleId);
                return role != null && Boolean.TRUE.equals(role.getActive());
            })
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<CommunityUserCapabilityGrant> directGrants = activeMember
            ? directGrantRepository.findByCommunityIdAndUserId(communityId, user.getId())
            : List.of();

        boolean communityAdmin = directGrants.stream()
            .map(CommunityUserCapabilityGrant::getCapability)
            .map(CommunityCapability::fromCode)
            .flatMap(Optional::stream)
            .anyMatch(capability ->
                capability == CommunityCapability.COMMUNITY_ADMIN
                    && capability.directGrantAllowed()
            );

        EnumSet<CommunityCapability> capabilities = EnumSet.noneOf(CommunityCapability.class);
        EnumMap<CommunityCapability, MutableScope> hierarchicalScopes =
            new EnumMap<>(CommunityCapability.class);

        if (owner || communityAdmin) {
            capabilities.addAll(EnumSet.allOf(CommunityCapability.class));
            Set<Integer> everyRole = allRoles.stream()
                .map(CommunityTeamRole::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            for (CommunityCapability capability : CommunityCapability.values()) {
                if (capability.hierarchicalTeamScope()) {
                    hierarchicalScopes.put(
                        capability,
                        new MutableScope(true, new LinkedHashSet<>(), new LinkedHashSet<>(everyRole))
                    );
                }
            }
        } else if (activeMember) {
            applyDirectGrants(directGrants, capabilities);

            if (!activeRoleIds.isEmpty()) {
                capabilities.add(CommunityCapability.TEAM_VIEW);
                List<CommunityTeamRoleCapability> roleGrants =
                    roleCapabilityRepository.findByCommunityIdAndRoleIdIn(communityId, activeRoleIds);
                Map<Integer, List<Integer>> childrenByParent = childrenByParent(allRoles);

                for (CommunityTeamRoleCapability grant : roleGrants) {
                    CommunityCapability.fromCode(grant.getCapability()).ifPresent(capability -> {
                        if (!capability.roleGrantAllowed()) {
                            return;
                        }

                        if (!capability.hierarchicalTeamScope()) {
                            capabilities.add(capability);
                            return;
                        }

                        if (!activeRoleIds.contains(grant.getRoleId())) {
                            return;
                        }

                        capabilities.add(capability);
                        MutableScope scope = hierarchicalScopes.computeIfAbsent(
                            capability,
                            ignored -> new MutableScope(false, new LinkedHashSet<>(), new LinkedHashSet<>())
                        );
                        scope.sourceRoleIds().add(grant.getRoleId());

                        Optional<Set<Integer>> descendants =
                            strictDescendants(grant.getRoleId(), childrenByParent);
                        if (descendants.isEmpty()) {
                            log.warn(
                                "Ignoring malformed Community Team hierarchy scope: communityId={}, sourceRoleId={}, capability={}",
                                communityId,
                                grant.getRoleId(),
                                capability
                            );
                            return;
                        }
                        scope.targetRoleIds().addAll(descendants.get());
                    });
                }
            }
        }

        applyCapabilityImplications(capabilities);

        Map<CommunityCapability, TeamScope> immutableScopes = new EnumMap<>(CommunityCapability.class);
        hierarchicalScopes.forEach((capability, scope) ->
            immutableScopes.put(
                capability,
                new TeamScope(
                    scope.unrestricted(),
                    immutableSortedSet(scope.sourceRoleIds()),
                    immutableSortedSet(scope.targetRoleIds())
                )
            )
        );

        return new CommunityAccessContext(
            community,
            user,
            owner,
            communityAdmin,
            activeMember,
            Collections.unmodifiableSet(EnumSet.copyOf(capabilities)),
            List.copyOf(activeRoleIds),
            Collections.unmodifiableMap(immutableScopes),
            immutableSortedSet(rolesById.keySet())
        );
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireCapability(
        Authentication authentication,
        Integer communityId,
        CommunityCapability capability
    ) {
        CommunityAccessContext context = resolve(authentication, communityId);
        if (!context.capabilities().contains(capability)) {
            throw forbidden("Permissão insuficiente para esta Community");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireTeamTarget(
        Authentication authentication,
        Integer communityId,
        CommunityCapability capability,
        Integer targetRoleId
    ) {
        if (!capability.hierarchicalTeamScope()) {
            throw new IllegalArgumentException("Capability não usa escopo hierárquico de Time");
        }

        CommunityAccessContext context = requireCapability(authentication, communityId, capability);
        if (!context.allRoleIds().contains(targetRoleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cargo interno não encontrado");
        }

        TeamScope scope = context.teamScopes().get(capability);
        if (scope == null || !scope.targetRoleIds().contains(targetRoleId)) {
            throw forbidden("O cargo está fora do seu escopo de gestão");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireTeamCreateParent(
        Authentication authentication,
        Integer communityId,
        CommunityCapability capability,
        Integer parentRoleId
    ) {
        requireHierarchicalCapability(capability);
        CommunityAccessContext context = requireCapability(authentication, communityId, capability);
        TeamScope scope = context.teamScopes().get(capability);

        if (scope != null && scope.unrestricted()) {
            if (parentRoleId != null && !context.allRoleIds().contains(parentRoleId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cargo interno não encontrado");
            }
            return context;
        }

        if (parentRoleId == null) {
            throw forbidden("Somente acesso irrestrito pode criar cargo raiz");
        }
        requireKnownRole(context, parentRoleId);

        Map<Integer, Set<Integer>> sourceScopes = sourceScopes(context, capability);
        boolean allowed = sourceScopes.entrySet().stream().anyMatch(entry ->
            Objects.equals(entry.getKey(), parentRoleId) || entry.getValue().contains(parentRoleId)
        );
        if (!allowed) {
            throw forbidden("O cargo superior está fora do seu escopo de gestão");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireTeamMove(
        Authentication authentication,
        Integer communityId,
        CommunityCapability capability,
        Integer targetRoleId,
        Integer newParentRoleId
    ) {
        requireHierarchicalCapability(capability);
        CommunityAccessContext context = requireCapability(authentication, communityId, capability);
        requireKnownRole(context, targetRoleId);
        if (newParentRoleId != null) {
            requireKnownRole(context, newParentRoleId);
        }

        TeamScope scope = context.teamScopes().get(capability);
        if (scope != null && scope.unrestricted()) {
            return context;
        }
        if (newParentRoleId == null) {
            throw forbidden("Cargo dentro de uma subárvore não pode ser movido para a raiz");
        }

        Map<Integer, Set<Integer>> sourceScopes = sourceScopes(context, capability);
        boolean allowed = sourceScopes.entrySet().stream().anyMatch(entry -> {
            Set<Integer> descendants = entry.getValue();
            return descendants.contains(targetRoleId)
                && (Objects.equals(entry.getKey(), newParentRoleId)
                    || descendants.contains(newParentRoleId));
        });
        if (!allowed) {
            throw forbidden("Cargo e novo superior devem permanecer dentro da mesma subárvore autorizada");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireTeamRoleSet(
        Authentication authentication,
        Integer communityId,
        CommunityCapability capability,
        Set<Integer> roleIds
    ) {
        requireHierarchicalCapability(capability);
        CommunityAccessContext context = requireCapability(authentication, communityId, capability);
        for (Integer roleId : roleIds) {
            requireKnownRole(context, roleId);
        }

        TeamScope scope = context.teamScopes().get(capability);
        if (scope != null && scope.unrestricted()) {
            return context;
        }
        if (roleIds.isEmpty()) {
            throw forbidden("Demanda sem cargo responsável exige acesso irrestrito");
        }

        boolean allowed = sourceScopes(context, capability).values().stream()
            .anyMatch(descendants -> descendants.containsAll(roleIds));
        if (!allowed) {
            throw forbidden("Todos os cargos da demanda devem estar dentro da mesma subárvore autorizada");
        }
        return context;
    }

    private Map<Integer, Set<Integer>> sourceScopes(
        CommunityAccessContext context,
        CommunityCapability capability
    ) {
        TeamScope scope = context.teamScopes().get(capability);
        if (scope == null || scope.sourceRoleIds().isEmpty()) {
            return Map.of();
        }

        List<CommunityTeamRole> allRoles =
            roleRepository.findByCommunityOrderByNameAsc(context.community());
        Map<Integer, List<Integer>> children = childrenByParent(allRoles);
        LinkedHashMap<Integer, Set<Integer>> result = new LinkedHashMap<>();

        for (Integer sourceRoleId : scope.sourceRoleIds()) {
            Optional<Set<Integer>> descendants = strictDescendants(sourceRoleId, children);
            if (descendants.isEmpty()) {
                log.warn(
                    "Ignoring malformed Community Team hierarchy source during mutation authorization: communityId={}, sourceRoleId={}, capability={}",
                    context.community().getId(),
                    sourceRoleId,
                    capability
                );
                continue;
            }
            result.put(sourceRoleId, immutableSortedSet(descendants.get()));
        }
        return Collections.unmodifiableMap(result);
    }

    private void requireKnownRole(CommunityAccessContext context, Integer roleId) {
        if (!context.allRoleIds().contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cargo interno não encontrado");
        }
    }

    private void requireHierarchicalCapability(CommunityCapability capability) {
        if (!capability.hierarchicalTeamScope()) {
            throw new IllegalArgumentException("Capability não usa escopo hierárquico de Time");
        }
    }

    @Transactional(readOnly = true)
    public CommunityAccessContext requireOwnerForCommunityAdminManagement(
        Authentication authentication,
        Integer communityId
    ) {
        CommunityAccessContext context = resolve(authentication, communityId);
        if (!context.owner()) {
            throw forbidden("Somente o proprietário da Community pode gerenciar Community Admins");
        }
        return context;
    }

    private void applyDirectGrants(
        List<CommunityUserCapabilityGrant> directGrants,
        EnumSet<CommunityCapability> capabilities
    ) {
        directGrants.stream()
            .map(CommunityUserCapabilityGrant::getCapability)
            .map(CommunityCapability::fromCode)
            .flatMap(Optional::stream)
            .filter(CommunityCapability::directGrantAllowed)
            .forEach(capabilities::add);
    }

    private void applyCapabilityImplications(EnumSet<CommunityCapability> capabilities) {
        if (capabilities.contains(CommunityCapability.MEMBER_DETAILS_VIEW)) {
            capabilities.add(CommunityCapability.MEMBERS_VIEW);
        }
        if (capabilities.contains(CommunityCapability.MEMBER_NOTES_MANAGE)) {
            capabilities.add(CommunityCapability.MEMBER_NOTES_VIEW);
        }
        if (
            capabilities.contains(CommunityCapability.TEAM_ASSIGN_MEMBERS)
                || capabilities.contains(CommunityCapability.TEAM_MANAGE_STRUCTURE)
                || capabilities.contains(CommunityCapability.TEAM_MANAGE_DEMANDS)
        ) {
            capabilities.add(CommunityCapability.TEAM_VIEW);
        }
    }

    boolean isAuthorizationEligibleMembership(
        UserCommunityStatus membership,
        boolean portariaEnabled
    ) {
        return membershipStatusResolver.resolve(membership, portariaEnabled)
            == CommunityMembershipStatus.ACTIVE;
    }

    boolean isAuthorizationEligibleMembership(UserCommunityStatus membership) {
        return membership != null
            && isAuthorizationEligibleMembership(
                membership,
                networkAvailability.isPortariaEnabled(membership.getCommunity())
            );
    }

    private Map<Integer, List<Integer>> childrenByParent(List<CommunityTeamRole> roles) {
        Map<Integer, List<Integer>> result = new HashMap<>();
        for (CommunityTeamRole role : roles) {
            if (role.getParentRole() == null || role.getParentRole().getId() == null) {
                continue;
            }
            result.computeIfAbsent(role.getParentRole().getId(), ignored -> new ArrayList<>())
                .add(role.getId());
        }
        result.values().forEach(children -> children.sort(Comparator.naturalOrder()));
        return result;
    }

    private Optional<Set<Integer>> strictDescendants(
        Integer sourceRoleId,
        Map<Integer, List<Integer>> childrenByParent
    ) {
        LinkedHashSet<Integer> descendants = new LinkedHashSet<>();
        HashSet<Integer> completed = new HashSet<>();
        HashSet<Integer> visiting = new HashSet<>();

        if (!collectDescendants(
            sourceRoleId,
            sourceRoleId,
            childrenByParent,
            visiting,
            completed,
            descendants
        )) {
            return Optional.empty();
        }

        descendants.remove(sourceRoleId);
        return Optional.of(descendants);
    }

    private boolean collectDescendants(
        Integer rootRoleId,
        Integer currentRoleId,
        Map<Integer, List<Integer>> childrenByParent,
        Set<Integer> visiting,
        Set<Integer> completed,
        Set<Integer> descendants
    ) {
        if (!visiting.add(currentRoleId)) {
            return false;
        }

        for (Integer childRoleId : childrenByParent.getOrDefault(currentRoleId, List.of())) {
            if (Objects.equals(childRoleId, rootRoleId)) {
                return false;
            }
            descendants.add(childRoleId);
            if (!completed.contains(childRoleId)
                && !collectDescendants(
                    rootRoleId,
                    childRoleId,
                    childrenByParent,
                    visiting,
                    completed,
                    descendants
                )) {
                return false;
            }
        }

        visiting.remove(currentRoleId);
        completed.add(currentRoleId);
        return true;
    }

    private CommunityAccessResponse toResponse(CommunityAccessContext context) {
        LinkedHashSet<String> capabilityCodes = context.capabilities().stream()
            .sorted(Comparator.comparing(Enum::name))
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<TeamCapabilityScope> scopes = context.teamScopes().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
            .map(entry -> new TeamCapabilityScope(
                entry.getKey().name(),
                entry.getValue().unrestricted(),
                List.copyOf(entry.getValue().sourceRoleIds()),
                List.copyOf(entry.getValue().targetRoleIds())
            ))
            .toList();

        return new CommunityAccessResponse(
            context.community().getId(),
            context.owner(),
            context.communityAdmin(),
            context.activeMember(),
            context.owner(),
            context.activeRoleIds(),
            Collections.unmodifiableSet(capabilityCodes),
            scopes
        );
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        String email = authentication.getName().trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuário autenticado não encontrado"
            ));
    }

    private Set<Integer> immutableSortedSet(Set<Integer> values) {
        LinkedHashSet<Integer> sorted = values.stream()
            .sorted()
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(sorted);
    }

    private ResponseStatusException communityNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Community não encontrada");
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private record MutableScope(
        boolean unrestricted,
        LinkedHashSet<Integer> sourceRoleIds,
        LinkedHashSet<Integer> targetRoleIds
    ) {}

    public record TeamScope(
        boolean unrestricted,
        Set<Integer> sourceRoleIds,
        Set<Integer> targetRoleIds
    ) {}

    public record CommunityAccessContext(
        Community community,
        User user,
        boolean owner,
        boolean communityAdmin,
        boolean activeMember,
        Set<CommunityCapability> capabilities,
        List<Integer> activeRoleIds,
        Map<CommunityCapability, TeamScope> teamScopes,
        Set<Integer> allRoleIds
    ) {}
}
