package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCommissionDtos.*;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * First vertical slice: private Pro commission cards and chronological queue.
 * No Community membership, commerce artist or browser-supplied user identifier.
 * Step templates and reference links are separate slices of the same domain.
 */
@Service
public class CreatorStudioCommissionService {
    private static final String FIELDS = """
        SELECT id,title,client_name,description,private_notes,
               requested_on,due_on,art_type_id,source_workflow_id,status,
               created_at,updated_at,completed_at,
               (SELECT s.name FROM creator_studio_commission_steps s
                 WHERE s.owner_user_id=creator_studio_commissions.owner_user_id
                   AND s.commission_id=creator_studio_commissions.id
                   AND s.status<>'DONE'
                 ORDER BY CASE s.status WHEN 'IN_PROGRESS' THEN 0
                   WHEN 'WAITING_APPROVAL' THEN 1 ELSE 2 END,s.position,s.id
                 LIMIT 1) AS current_stage_name,
               (SELECT s.stage_definition_id FROM creator_studio_commission_steps s
                 WHERE s.owner_user_id=creator_studio_commissions.owner_user_id
                   AND s.commission_id=creator_studio_commissions.id
                   AND s.status<>'DONE'
                 ORDER BY CASE s.status WHEN 'IN_PROGRESS' THEN 0
                   WHEN 'WAITING_APPROVAL' THEN 1 ELSE 2 END,s.position,s.id
                 LIMIT 1) AS current_stage_definition_id,
               (SELECT s.position FROM creator_studio_commission_steps s
                 WHERE s.owner_user_id=creator_studio_commissions.owner_user_id
                   AND s.commission_id=creator_studio_commissions.id
                   AND s.status<>'DONE'
                 ORDER BY CASE s.status WHEN 'IN_PROGRESS' THEN 0
                   WHEN 'WAITING_APPROVAL' THEN 1 ELSE 2 END,s.position,s.id
                 LIMIT 1) AS current_stage_position
          FROM creator_studio_commissions
        """;

    private static final RowMapper<Commission> COMMISSION_MAPPER =
        (rs, row) -> new Commission(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("client_name"),
            rs.getString("description"),
            rs.getString("private_notes"),
            rs.getDate("requested_on").toLocalDate(),
            rs.getDate("due_on") == null ? null : rs.getDate("due_on").toLocalDate(),
            nullableLong(rs, "art_type_id"),
            Status.valueOf(rs.getString("status")),
            timestamp(rs, "created_at"),
            timestamp(rs, "updated_at"),
            timestamp(rs, "completed_at"),
            rs.getString("current_stage_name"),
            nullableLong(rs, "current_stage_definition_id"),
            nullableLong(rs, "source_workflow_id"),
            nullableInteger(rs, "current_stage_position")
        );

    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;

    public CreatorStudioCommissionService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String field) throws SQLException {
        int value = rs.getInt(field);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime timestamp(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toLocalDateTime();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Commission não encontrada");
    }

    private void validate(SaveCommission input) {
        if (input == null || input.title() == null || input.title().isBlank()
            || input.title().length() > 160
            || input.clientName() == null || input.clientName().isBlank()
            || input.clientName().length() > 120
            || input.requestedOn() == null
            || (input.description() != null && input.description().length() > 8000)
            || (input.privateNotes() != null && input.privateNotes().length() > 8000)
            || (input.artTypeId() != null && input.artTypeId() <= 0)
            || (input.workflowId() != null && input.workflowId() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Dados da commission inválidos");
        }
    }

    private void validateType(int ownerId, Long typeId) {
        if (typeId == null) return;
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_art_types
             WHERE owner_user_id=? AND id=?
            """, Integer.class, ownerId, typeId);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tipo de arte indisponível para este artista");
        }
    }

    private Long workflowToCopy(int owner, Long artTypeId, Long explicitWorkflowId) {
        Long chosen = explicitWorkflowId;
        if (chosen == null && artTypeId != null) {
            List<Long> rows = jdbc.query("""
                SELECT workflow_id FROM creator_studio_art_types
                 WHERE owner_user_id=? AND id=?
                """, (rs, n) -> nullableLong(rs, "workflow_id"), owner, artTypeId);
            if (!rows.isEmpty()) chosen = rows.get(0);
        }
        if (chosen == null) {
            List<Long> defaults = jdbc.query("""
                SELECT id FROM creator_studio_workflows
                 WHERE owner_user_id=? AND is_default=1
                """, (rs, n) -> rs.getLong("id"), owner);
            if (!defaults.isEmpty()) chosen = defaults.get(0);
        }
        if (chosen != null) {
            Integer owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM creator_studio_workflows
                 WHERE owner_user_id=? AND id=?
                """, Integer.class, owner, chosen);
            if (owned == null || owned != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Modelo de etapas não pertence a este artista");
            }
        }
        return chosen;
    }

    @Transactional
    public Commission create(Authentication auth, SaveCommission input) {
        int ownerId = access.requirePro(auth);
        validate(input);
        validateType(ownerId, input.artTypeId());
        Long workflowId = workflowToCopy(ownerId, input.artTypeId(), input.workflowId());

        String sql = """
            INSERT INTO creator_studio_commissions
                (owner_user_id,art_type_id,source_workflow_id,title,client_name,
                 description,private_notes,requested_on,due_on,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,'QUEUED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """;
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, ownerId);
            setType(ps, 2, input.artTypeId());
            setType(ps, 3, workflowId);
            ps.setString(4, input.title().trim());
            ps.setString(5, input.clientName().trim());
            ps.setString(6, input.description());
            ps.setString(7, input.privateNotes());
            ps.setDate(8, Date.valueOf(input.requestedOn()));
            if (input.dueOn() == null) ps.setNull(9, Types.DATE);
            else ps.setDate(9, Date.valueOf(input.dueOn()));
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Commission sem ID gerado");
        if (workflowId != null) {
            // Snapshot in the SAME transaction as the commission insert.
            // Future template edits never retroactively alter the order.
            jdbc.update("""
                INSERT INTO creator_studio_commission_steps
                    (owner_user_id,commission_id,stage_definition_id,name,position,status)
                SELECT ?,?,stage_definition_id,name,position,'PENDING'
                  FROM creator_studio_workflow_steps
                 WHERE owner_user_id=? AND workflow_id=?
                """, ownerId, id.longValue(), ownerId, workflowId);
        }
        return byOwner(ownerId, id.longValue());
    }

    private static void setType(PreparedStatement ps, int index, Long typeId)
        throws SQLException {
        if (typeId == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, typeId);
    }

    @Transactional(readOnly = true)
    public Commission find(Authentication auth, long id) {
        return byOwner(access.requirePro(auth), id);
    }

    private Commission byOwner(int ownerId, long id) {
        List<Commission> matches = jdbc.query(
            FIELDS + " WHERE owner_user_id=? AND id=?",
            COMMISSION_MAPPER, ownerId, id);
        if (matches.isEmpty()) throw notFound();
        return matches.get(0);
    }

    /**
     * Defaults to the active queue, oldest requested date first.
     * Explicit status filter is opt-in (including COMPLETED history).
     * No offset, sort column, owner ID or SQL fragment is accepted from user input.
     */
    @Transactional(readOnly = true)
    public CommissionPage list(Authentication auth, Status status, int page, int pageSize) {
        int ownerId = access.requirePro(auth);
        if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Paginação inválida");
        }
        int offset = (page - 1) * pageSize;
        boolean activeOnly = status == null;
        String condition = activeOnly ? " AND status <> 'COMPLETED'" : " AND status=?";
        String countSql = "SELECT COUNT(*) FROM creator_studio_commissions"
            + " WHERE owner_user_id=?" + condition;
        Long count = activeOnly
            ? jdbc.queryForObject(countSql, Long.class, ownerId)
            : jdbc.queryForObject(countSql, Long.class, ownerId, status.name());
        List<Commission> items = activeOnly
            ? jdbc.query(FIELDS + " WHERE owner_user_id=?" + condition
                + " ORDER BY requested_on ASC,id ASC LIMIT ? OFFSET ?",
                COMMISSION_MAPPER, ownerId, pageSize, offset)
            : jdbc.query(FIELDS + " WHERE owner_user_id=?" + condition
                + " ORDER BY requested_on ASC,id ASC LIMIT ? OFFSET ?",
                COMMISSION_MAPPER, ownerId, status.name(), pageSize, offset);
        return new CommissionPage(items, page, pageSize, count == null ? 0 : count);
    }

    /**
     * Scoped filters for process and reusable-stage views. Artist ownership
     * comes from the authenticated principal; no Community or user ID input.
     */
    @Transactional(readOnly = true)
    public CommissionPage filteredList(Authentication auth, Status status, int page, int pageSize,
                                       Long workflowId, Long stageDefinitionId, boolean unlinked) {
        if (workflowId == null && stageDefinitionId == null && !unlinked)
            return list(auth, status, page, pageSize);
        int ownerId = access.requirePro(auth);
        if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100
                || (workflowId != null && workflowId <= 0)
                || (stageDefinitionId != null && stageDefinitionId <= 0)
                || (unlinked && stageDefinitionId != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro inválido");
        }
        StringBuilder where = new StringBuilder(" WHERE owner_user_id=?");
        List<Object> params = new ArrayList<>();
        params.add(ownerId);
        if (status == null) where.append(" AND status <> 'COMPLETED'");
        else {
            where.append(" AND status=?");
            params.add(status.name());
        }
        if (workflowId != null) {
            where.append(" AND source_workflow_id=?");
            params.add(workflowId);
        }
        // Match the exact current-stage selection in FIELDS; checking all
        // non-DONE steps would incorrectly include future pending stages.
        if (stageDefinitionId != null || unlinked) {
            where.append("""
                AND (SELECT s.stage_definition_id
                       FROM creator_studio_commission_steps s
                      WHERE s.owner_user_id=creator_studio_commissions.owner_user_id
                        AND s.commission_id=creator_studio_commissions.id
                        AND s.status<>'DONE'
                      ORDER BY CASE s.status WHEN 'IN_PROGRESS' THEN 0
                        WHEN 'WAITING_APPROVAL' THEN 1 ELSE 2 END,s.position,s.id
                      LIMIT 1)
                """);
            if (unlinked) where.append(" IS NULL");
            else {
                where.append("=?");
                params.add(stageDefinitionId);
            }
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM creator_studio_commissions"
            + where, Long.class, params.toArray());
        List<Object> pageArgs = new ArrayList<>(params);
        pageArgs.add(pageSize);
        pageArgs.add((page - 1) * pageSize);
        List<Commission> rows = jdbc.query(FIELDS + where
            + " ORDER BY requested_on ASC,id ASC LIMIT ? OFFSET ?",
            COMMISSION_MAPPER, pageArgs.toArray());
        return new CommissionPage(rows, page, pageSize, count == null ? 0 : count);
    }

    @Transactional
    public Commission update(Authentication auth, long id, SaveCommission input) {
        int ownerId = access.requirePro(auth);
        validate(input);
        validateType(ownerId, input.artTypeId());
        int updated = jdbc.update("""
            UPDATE creator_studio_commissions
               SET title=?,client_name=?,description=?,private_notes=?,
                   requested_on=?,due_on=?,art_type_id=?,updated_at=UTC_TIMESTAMP(6)
             WHERE id=? AND owner_user_id=?
            """,
            input.title().trim(), input.clientName().trim(),
            input.description(), input.privateNotes(), input.requestedOn(),
            input.dueOn(), input.artTypeId(), id, ownerId);
        if (updated != 1) throw notFound();
        return byOwner(ownerId, id);
    }

    @Transactional
    public Commission changeStatus(Authentication auth, long id, ChangeStatus input) {
        int ownerId = access.requirePro(auth);
        if (input == null || input.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido");
        }
        int updated = jdbc.update("""
            UPDATE creator_studio_commissions
               SET status=?,
                   completed_at=CASE
                      WHEN ?='COMPLETED'
                      THEN COALESCE(completed_at,UTC_TIMESTAMP(6))
                      ELSE NULL END,
                   updated_at=UTC_TIMESTAMP(6)
             WHERE id=? AND owner_user_id=?
            """, input.status().name(), input.status().name(), id, ownerId);
        if (updated != 1) throw notFound();
        return byOwner(ownerId, id);
    }
}
