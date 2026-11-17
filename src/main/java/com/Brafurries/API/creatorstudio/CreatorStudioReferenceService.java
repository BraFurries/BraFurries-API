package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioDetailsDtos.*;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reference URLs only; the backend neither downloads links nor stores R2 files.
 * Every operation is guarded by PRO and the signed-in user ownership.
 */
@Service
public class CreatorStudioReferenceService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;

    private static final RowMapper<Reference> MAPPER = (rs, n) ->
        new Reference(rs.getLong("id"), rs.getLong("commission_id"),
            rs.getString("label"), rs.getString("url"), rs.getInt("position"));

    public CreatorStudioReferenceService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Commission ou referência não encontrada");
    }

    private static String safeUrl(String input) {
        if (input == null || input.isBlank() || input.length() > 1500 ||
            !input.equals(input.trim()) || input.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL inválida");
        }
        try {
            URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use um endereço HTTPS válido, sem credenciais ou fragmentos");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL inválida");
        }
    }

    private static String label(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        if (trimmed.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rótulo muito longo");
        }
        return trimmed;
    }

    private void lockOwnerCommission(int ownerId, long commissionId) {
        List<Long> found = jdbc.query("""
            SELECT id FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=? FOR UPDATE
            """, (rs, n) -> rs.getLong("id"), ownerId, commissionId);
        if (found.isEmpty()) throw notFound();
    }

    private void requireOwnedCommission(int ownerId, long commissionId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=?
            """, Integer.class, ownerId, commissionId);
        if (count == null || count != 1) throw notFound();
    }

    @Transactional(readOnly = true)
    public List<Reference> list(Authentication auth, long commissionId) {
        int ownerId = access.requirePro(auth);
        requireOwnedCommission(ownerId, commissionId);
        return jdbc.query("""
            SELECT id,commission_id,label,url,position
              FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=?
             ORDER BY position,id
            """, MAPPER, ownerId, commissionId);
    }

    private Reference byOwner(int ownerId, long commissionId, long referenceId) {
        List<Reference> found = jdbc.query("""
            SELECT id,commission_id,label,url,position
              FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, MAPPER, ownerId, commissionId, referenceId);
        if (found.isEmpty()) throw notFound();
        return found.get(0);
    }

    @Transactional
    public Reference create(Authentication auth, long commissionId, ReferenceDraft input) {
        int ownerId = access.requirePro(auth);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String url = safeUrl(input.url());
        String name = label(input.label());
        lockOwnerCommission(ownerId, commissionId);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=?
            """, Integer.class, ownerId, commissionId);
        if (count == null || count >= 30) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Limite de 30 referências por commission");
        }
        Integer next = jdbc.queryForObject("""
            SELECT COALESCE(MAX(position),-1)+1 FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=?
            """, Integer.class, ownerId, commissionId);
        if (next == null || next >= 65535) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Posições esgotadas");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO creator_studio_reference_links
                    (owner_user_id,commission_id,label,url,position)
                VALUES (?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, ownerId);
            ps.setLong(2, commissionId);
            if (name == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, name);
            ps.setString(4, url);
            ps.setInt(5, next);
            return ps;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Referência sem ID");
        return byOwner(ownerId, commissionId, keys.getKey().longValue());
    }

    @Transactional
    public Reference update(Authentication auth, long commissionId, long referenceId,
                            ReferenceDraft input) {
        int ownerId = access.requirePro(auth);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String url = safeUrl(input.url());
        String name = label(input.label());
        lockOwnerCommission(ownerId, commissionId);
        // Checking ownership separately avoids MariaDB no-op UPDATE being mistaken for 404.
        byOwner(ownerId, commissionId, referenceId);
        jdbc.update("""
            UPDATE creator_studio_reference_links SET label=?,url=?
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, name, url, ownerId, commissionId, referenceId);
        return byOwner(ownerId, commissionId, referenceId);
    }

    @Transactional
    public void delete(Authentication auth, long commissionId, long referenceId) {
        int ownerId = access.requirePro(auth);
        lockOwnerCommission(ownerId, commissionId);
        int changed = jdbc.update("""
            DELETE FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, ownerId, commissionId, referenceId);
        if (changed != 1) throw notFound();
    }
}
