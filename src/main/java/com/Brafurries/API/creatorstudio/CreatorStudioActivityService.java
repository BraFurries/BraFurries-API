package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioDetailsDtos.*;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
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
 * This private journal records what the artist reports, NOT signed client
 * approval. Entries are append-only for a consistent manual history.
 */
@Service
public class CreatorStudioActivityService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;
    private static final RowMapper<Activity> MAPPER = (rs, n) ->
        new Activity(rs.getLong("id"), rs.getLong("commission_id"),
            ActivityKind.valueOf(rs.getString("event_kind")),
            rs.getString("description"), rs.getDate("occurred_on").toLocalDate(),
            rs.getObject("created_at", LocalDateTime.class));

    public CreatorStudioActivityService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Commission não encontrada");
    }

    private void owned(int ownerId, long commissionId) {
        Integer present = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=?
            """, Integer.class, ownerId, commissionId);
        if (present == null || present != 1) throw notFound();
    }

    @Transactional(readOnly = true)
    public ActivityPage list(Authentication auth, long commissionId, int page, int pageSize) {
        int ownerId = access.requirePro(auth);
        if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paginação inválida");
        }
        owned(ownerId, commissionId);
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_activity
             WHERE owner_user_id=? AND commission_id=?
            """, Long.class, ownerId, commissionId);
        List<Activity> items = jdbc.query("""
            SELECT id,commission_id,event_kind,description,occurred_on,created_at
              FROM creator_studio_activity
             WHERE owner_user_id=? AND commission_id=?
             ORDER BY occurred_on DESC,id DESC LIMIT ? OFFSET ?
            """, MAPPER, ownerId, commissionId, pageSize, (page-1)*pageSize);
        return new ActivityPage(items, page, pageSize, count == null ? 0 : count);
    }

    @Transactional
    public Activity create(Authentication auth, long commissionId, ActivityDraft draft) {
        int ownerId = access.requirePro(auth);
        if (draft == null || draft.eventKind() == null
            || draft.occurredOn() == null
            || draft.description() == null
            || draft.description().isBlank()
            || draft.description().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Preencha tipo, data e descrição da atualização");
        }
        String note = draft.description().trim();
        owned(ownerId, commissionId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO creator_studio_activity
                    (owner_user_id,commission_id,event_kind,description,occurred_on,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, ownerId);
            ps.setLong(2, commissionId);
            ps.setString(3, draft.eventKind().name());
            ps.setString(4, note);
            ps.setDate(5, Date.valueOf(draft.occurredOn()));
            return ps;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Registro sem ID");

        List<Activity> created = jdbc.query("""
            SELECT id,commission_id,event_kind,description,occurred_on,created_at
              FROM creator_studio_activity
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, MAPPER, ownerId, commissionId, keys.getKey().longValue());
        if (created.isEmpty()) throw new IllegalStateException("Registro não encontrado");
        return created.get(0);
    }
}
