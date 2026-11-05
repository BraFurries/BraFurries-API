package com.Brafurries.API.platform;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * BraFurries-private security boundary.
 *
 * Community roles provide only a permission signal. An explicit owner-approved
 * person grant is also required; Community Admin never receives implicit access.
 * No platform administrative data is partitioned by Community.
 */
@Service
public class PlatformAuthorizationService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final int ownerUserId;
    private final int officialCommunityId;

    public PlatformAuthorizationService(
        UserRepository users,
        JdbcTemplate jdbc,
        @Value("${app.platform.owner-user-id:0}") int ownerUserId,
        @Value("${app.platform.official-community-id:0}") int officialCommunityId
    ) {
        this.users = users;
        this.jdbc = jdbc;
        this.ownerUserId = ownerUserId;
        this.officialCommunityId = officialCommunityId;
    }

    public record Access(boolean owner, boolean enabled, Set<PlatformPermission> permissions) {
        public boolean has(PlatformPermission permission) {
            return owner || permissions.contains(permission);
        }
    }

    public record Actor(User user, Access access) {}

    public boolean isConfigured() {
        return ownerUserId > 0 && officialCommunityId > 0;
    }

    public int officialCommunityId() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Permissões administrativas indisponíveis");
        }
        return officialCommunityId;
    }

    public User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        String email = authentication.getName().trim().toLowerCase(Locale.ROOT);
        return users.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Usuário autenticado não encontrado"
            ));
    }

    @Transactional(readOnly = true)
    public Actor resolve(Authentication auth) {
        User user = currentUser(auth);
        if (!isConfigured()) {
            return new Actor(user, new Access(false, false, Set.of()));
        }
        if (user.getId().equals(ownerUserId)) {
            return new Actor(user, new Access(true, true,
                Set.copyOf(EnumSet.allOf(PlatformPermission.class))));
        }

        // Compute intersection at request time (never trust JWT roles/claims).
        // The Time role MUST be active and belong to the configured BraFurries
        // Community, with an active membership. No other Community participates.
        List<String> codes = jdbc.queryForList("""
            SELECT DISTINCT personal.permission_code
              FROM platform_authorized_users approved
              JOIN platform_user_permissions personal
                ON personal.user_id = approved.user_id
              JOIN platform_role_permissions team_grant
                ON team_grant.permission_code = personal.permission_code
              JOIN platform_permission_catalog catalog
                ON catalog.code = personal.permission_code AND catalog.active = 1
              JOIN community_team_roles role
                ON role.id = team_grant.role_id
               AND role.community_id = ?
               AND role.active = 1
              JOIN community_team_role_assignments assignment
                ON assignment.role_id = role.id
               AND assignment.community_id = role.community_id
               AND assignment.user_id = approved.user_id
              JOIN user_community_status membership
                ON membership.community_id = role.community_id
               AND membership.user_id = approved.user_id
             WHERE approved.user_id = ?
               AND membership.is_present = 1
               AND COALESCE(membership.banned, 0) = 0
               AND (membership.approval_required = 0
                    OR membership.approved = 1)
            """, String.class, officialCommunityId, user.getId());

        EnumSet<PlatformPermission> permissions = EnumSet.noneOf(PlatformPermission.class);
        for (String code : codes) {
            // DB CHECK only allows canonical permission strings.
            try {
                permissions.add(PlatformPermission.valueOf(code));
            } catch (IllegalArgumentException unknownPermission) {
                // Unknown catalog additions must fail closed until authorized in API.
            }
        }
        return new Actor(user, new Access(false, true, Set.copyOf(permissions)));
    }

    @Transactional(readOnly = true)
    public Actor require(Authentication auth, PlatformPermission permission) {
        Actor actor = resolve(auth);
        if (!actor.access().has(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Sem permissão para esta área da plataforma");
        }
        return actor;
    }

    @Transactional(readOnly = true)
    public User requireOwner(Authentication auth) {
        Actor actor = resolve(auth);
        if (!actor.access().owner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Somente o responsável pela BraFurries pode alterar as permissões");
        }
        return actor.user();
    }
}
