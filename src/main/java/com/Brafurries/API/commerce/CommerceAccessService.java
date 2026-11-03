package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-controlled commercial allowlist and independent Time-role grants.
 * All writes and audits are transactional; no Community capabilities are changed.
 */
@Service
public class CommerceAccessService {
    private final CommerceAuthorizationService authorization;
    private final JdbcTemplate jdbc;

    public CommerceAccessService(
        CommerceAuthorizationService authorization,
        JdbcTemplate jdbc
    ) {
        this.authorization = authorization;
        this.jdbc = jdbc;
    }

    public record AccessView(boolean enabled, boolean owner, List<String> permissions) {}
    public record PersonView(int userId, String name, List<String> permissions) {}
    public record RoleView(int roleId, String name, boolean active, List<String> permissions) {}

    @Transactional(readOnly = true)
    public AccessView myAccess(Authentication auth) {
        CommerceAuthorizationService.Access access = authorization.resolve(auth).access();
        return new AccessView(
            access.enabled(), access.owner(),
            access.permissions().stream().map(Enum::name).sorted().toList()
        );
    }

    @Transactional(readOnly = true)
    public List<PersonView> listPeople(Authentication auth) {
        authorization.requireOwner(auth);
        return jdbc.query("""
            SELECT allowed.user_id, u.display_name, u.username,
                   GROUP_CONCAT(grant.permission_code ORDER BY grant.permission_code
                       SEPARATOR ',') AS permission_codes
              FROM commerce_authorized_users allowed
              JOIN users u ON u.id = allowed.user_id
              LEFT JOIN commerce_user_permissions grant ON grant.user_id = allowed.user_id
             GROUP BY allowed.user_id, u.display_name, u.username
             ORDER BY COALESCE(u.display_name, u.username, ''), allowed.user_id
            """, (rs, index) -> new PersonView(
                rs.getInt("user_id"),
                displayName(rs.getString("display_name"), rs.getString("username"),
                    rs.getInt("user_id")),
                permissionList(rs.getString("permission_codes"))
            ));
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles(Authentication auth) {
        authorization.requireOwner(auth);
        return jdbc.query("""
            SELECT role.id, role.name, role.active,
                   GROUP_CONCAT(grant.permission_code ORDER BY grant.permission_code
                       SEPARATOR ',') AS permission_codes
              FROM community_team_roles role
              LEFT JOIN commerce_role_permissions grant ON grant.role_id = role.id
             WHERE role.community_id = ?
             GROUP BY role.id, role.name, role.active
             ORDER BY role.name, role.id
            """, (rs, index) -> new RoleView(
                rs.getInt("id"), rs.getString("name"), rs.getBoolean("active"),
                permissionList(rs.getString("permission_codes"))
            ), authorization.officialCommunityId());
    }

    @Transactional
    public void approvePerson(Authentication auth, int userId) {
        User owner = authorization.requireOwner(auth);
        if (owner.getId().equals(userId)) {
            throw invalid("O responsável já possui acesso integral");
        }
        lockUser(userId);
        if (exists("SELECT COUNT(*) FROM commerce_authorized_users WHERE user_id = ?", userId)) {
            return;
        }
        jdbc.update("""
            INSERT INTO commerce_authorized_users (user_id, approved_by_user_id)
            VALUES (?, ?)
            """, userId, owner.getId());
        audit(owner.getId(), "PERSON_APPROVED", userId, null, null);
    }

    @Transactional
    public void revokePerson(Authentication auth, int userId) {
        User owner = authorization.requireOwner(auth);
        if (owner.getId().equals(userId)) {
            throw invalid("O responsável não pode revogar a própria autoridade");
        }
        lockUser(userId);
        if (jdbc.update("DELETE FROM commerce_authorized_users WHERE user_id = ?", userId) > 0) {
            // ON DELETE CASCADE also erases personal permissions, so later
            // reapproval must explicitly grant rights again.
            audit(owner.getId(), "PERSON_REVOKED", userId, null, null);
        }
    }

    @Transactional
    public void grantPersonPermission(Authentication auth, int userId, String code) {
        User owner = authorization.requireOwner(auth);
        CommercePermission permission = CommercePermission.parse(code);
        if (owner.getId().equals(userId)) {
            throw invalid("O responsável não precisa de permissão delegada");
        }
        lockUser(userId);
        requireApproved(userId);
        if (exists("SELECT COUNT(*) FROM commerce_user_permissions WHERE user_id = ? AND permission_code = ?",
                userId, permission.name())) return;
        jdbc.update("""
            INSERT INTO commerce_user_permissions (user_id, permission_code, granted_by_user_id)
            VALUES (?, ?, ?)
            """, userId, permission.name(), owner.getId());
        audit(owner.getId(), "PERSON_PERMISSION_GRANTED", userId, null, permission.name());
    }

    @Transactional
    public void revokePersonPermission(Authentication auth, int userId, String code) {
        User owner = authorization.requireOwner(auth);
        CommercePermission permission = CommercePermission.parse(code);
        lockUser(userId);
        if (jdbc.update("""
            DELETE FROM commerce_user_permissions
             WHERE user_id = ? AND permission_code = ?
            """, userId, permission.name()) > 0) {
            audit(owner.getId(), "PERSON_PERMISSION_REVOKED", userId, null, permission.name());
        }
    }

    @Transactional
    public void grantRolePermission(Authentication auth, int roleId, String code) {
        User owner = authorization.requireOwner(auth);
        CommercePermission permission = CommercePermission.parse(code);
        lockOfficialRole(roleId);
        if (exists("SELECT COUNT(*) FROM commerce_role_permissions WHERE role_id = ? AND permission_code = ?",
                roleId, permission.name())) return;
        jdbc.update("""
            INSERT INTO commerce_role_permissions (role_id, permission_code, granted_by_user_id)
            VALUES (?, ?, ?)
            """, roleId, permission.name(), owner.getId());
        audit(owner.getId(), "ROLE_PERMISSION_GRANTED", null, roleId, permission.name());
    }

    @Transactional
    public void revokeRolePermission(Authentication auth, int roleId, String code) {
        User owner = authorization.requireOwner(auth);
        CommercePermission permission = CommercePermission.parse(code);
        lockOfficialRole(roleId);
        if (jdbc.update("""
            DELETE FROM commerce_role_permissions
             WHERE role_id = ? AND permission_code = ?
            """, roleId, permission.name()) > 0) {
            audit(owner.getId(), "ROLE_PERMISSION_REVOKED", null, roleId, permission.name());
        }
    }

    private void lockUser(int userId) {
        List<Integer> ids = jdbc.queryForList(
            "SELECT id FROM users WHERE id = ? FOR UPDATE", Integer.class, userId
        );
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
    }

    private void lockOfficialRole(int roleId) {
        List<Integer> ids = jdbc.queryForList("""
            SELECT id FROM community_team_roles
             WHERE id = ? AND community_id = ? FOR UPDATE
            """, Integer.class, roleId, authorization.officialCommunityId());
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Cargo do Time da BraFurries não encontrado");
        }
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private void requireApproved(int userId) {
        if (!exists("SELECT COUNT(*) FROM commerce_authorized_users WHERE user_id = ?", userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Primeiro aprove esta pessoa para a Gestão Comercial");
        }
    }

    private void audit(Integer actor, String action, Integer person,
                       Integer role, String permission) {
        jdbc.update("""
            INSERT INTO commerce_access_audit
                (actor_user_id, action, subject_user_id, role_id, permission_code)
            VALUES (?, ?, ?, ?, ?)
            """, actor, action, person, role, permission);
    }

    private static String displayName(String displayName, String username, int id) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (username != null && !username.isBlank()) return username;
        return "Usuário " + id;
    }

    private static List<String> permissionList(String codes) {
        if (codes == null || codes.isBlank()) return List.of();
        return List.of(codes.split(","));
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
