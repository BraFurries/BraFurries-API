package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioProductionDtos.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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

/** The order's steps are a snapshot, never a live link to a workflow template. */
@Service
public class CreatorStudioStepService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;

    private static final RowMapper<CommissionStep> STEP_MAPPER = (rs, i) -> {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new CommissionStep(
            rs.getLong("id"), rs.getLong("commission_id"),
            rs.getString("name"), nullableLong(rs, "stage_definition_id"),
            rs.getInt("position"), StepStatus.valueOf(rs.getString("status")),
            rs.getString("notes"),
            completedAt == null ? null : completedAt.toLocalDateTime());
    };

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    public CreatorStudioStepService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Commission ou etapa não encontrada");
    }

    private void lockCommission(int owner, long commissionId) {
        List<Long> found = jdbc.query("""
            SELECT id FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=? FOR UPDATE
            """, (rs, i) -> rs.getLong("id"), owner, commissionId);
        if (found.isEmpty()) throw notFound();
    }

    private void checkSharedStage(int owner, Long id) {
        if (id == null) return;
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Etapa inválida");
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_stage_definitions
             WHERE owner_user_id=? AND id=?
            """, Integer.class, owner, id);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Definição de etapa não pertence a este artista");
        }
    }

    private static String checkName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da etapa inválido");
        }
        return name.trim();
    }

    private CommissionStep findOwned(int owner, long commissionId, long stepId) {
        List<CommissionStep> found = jdbc.query("""
            SELECT id,commission_id,name,stage_definition_id,position,status,notes,completed_at
              FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, STEP_MAPPER, owner, commissionId, stepId);
        if (found.isEmpty()) throw notFound();
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public List<CommissionStep> list(Authentication auth, long commissionId) {
        int owner = access.requirePro(auth);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=?
            """, Integer.class, owner, commissionId);
        if (count == null || count != 1) throw notFound();
        return jdbc.query("""
            SELECT id,commission_id,name,stage_definition_id,position,status,notes,completed_at
              FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=?
             ORDER BY position,id
            """, STEP_MAPPER, owner, commissionId);
    }

    @Transactional
    public CommissionStep append(Authentication auth, long commissionId, StepDraft draft) {
        int owner = access.requirePro(auth);
        if (draft == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String name = checkName(draft.name());
        checkSharedStage(owner, draft.stageDefinitionId());
        lockCommission(owner, commissionId);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=?
            """, Integer.class, owner, commissionId);
        if (count == null || count >= 40) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Limite de 40 etapas por commission");
        }
        Integer nextPosition = jdbc.queryForObject("""
            SELECT COALESCE(MAX(position),-1)+1 FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=?
            """, Integer.class, owner, commissionId);
        if (nextPosition == null || nextPosition >= 65535) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ordem de etapas esgotada");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO creator_studio_commission_steps
                   (owner_user_id,commission_id,stage_definition_id,name,position)
                VALUES (?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, owner);
            ps.setLong(2, commissionId);
            if (draft.stageDefinitionId() == null) ps.setNull(3, java.sql.Types.BIGINT);
            else ps.setLong(3, draft.stageDefinitionId());
            ps.setString(4, name);
            ps.setInt(5, nextPosition);
            return ps;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Etapa sem ID");
        return findOwned(owner, commissionId, keys.getKey().longValue());
    }

    @Transactional
    public List<CommissionStep> importWorkflow(Authentication auth,
                                               long commissionId, long workflowId) {
        int owner = access.requirePro(auth);
        lockCommission(owner, commissionId);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=?
            """, Integer.class, owner, commissionId);
        if (count == null || count != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "O pedido já possui etapas; não sobrescrever o andamento");
        }
        Integer exists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_workflows
             WHERE owner_user_id=? AND id=?
            """, Integer.class, owner, workflowId);
        if (exists == null || exists != 1) throw notFound();
        jdbc.update("""
            INSERT INTO creator_studio_commission_steps
                (owner_user_id,commission_id,stage_definition_id,name,position,status)
            SELECT ?,?,stage_definition_id,name,position,'PENDING'
              FROM creator_studio_workflow_steps
             WHERE owner_user_id=? AND workflow_id=?
            """, owner, commissionId, owner, workflowId);
        return jdbc.query("""
            SELECT id,commission_id,name,stage_definition_id,position,status,notes,completed_at
              FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=? ORDER BY position,id
            """, STEP_MAPPER, owner, commissionId);
    }

    @Transactional
    public CommissionStep update(Authentication auth, long commissionId,
                                 long stepId, UpdateStep input) {
        int owner = access.requirePro(auth);
        if (input == null || input.status() == null ||
            (input.notes() != null && input.notes().length() > 8000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Etapa inválida");
        }
        String name = checkName(input.name());
        checkSharedStage(owner, input.stageDefinitionId());
        lockCommission(owner, commissionId);
        findOwned(owner, commissionId, stepId);

        // Only one production step can be active. Pausing the commission does
        // not touch this stage or move progress into an unrelated "Paused" step.
        if (input.status() == StepStatus.IN_PROGRESS ||
            input.status() == StepStatus.WAITING_APPROVAL) {
            jdbc.update("""
                UPDATE creator_studio_commission_steps
                   SET status='PENDING',completed_at=NULL
                 WHERE owner_user_id=? AND commission_id=? AND id<>?
                   AND status IN ('IN_PROGRESS','WAITING_APPROVAL')
                """, owner, commissionId, stepId);
        }
        // The commission and step are both checked under the parent lock.
        // An unchanged PUT may affect zero rows on MariaDB and remains valid.
        jdbc.update("""
            UPDATE creator_studio_commission_steps
               SET name=?,stage_definition_id=?,status=?,notes=?,
                   completed_at=CASE WHEN ?='DONE'
                     THEN COALESCE(completed_at,UTC_TIMESTAMP(6)) ELSE NULL END
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, name, input.stageDefinitionId(), input.status().name(),
            input.notes(), input.status().name(), owner, commissionId, stepId);
        return findOwned(owner, commissionId, stepId);
    }

    /**
     * Explicit, atomic progress transition. Only the authenticated artist's
     * *actual copied* commission step is a valid destination. Never infer
     * steps from workflow templates (which may have changed since creation).
     * Notes and the commission's independent QUEUED/PAUSED/etc status remain.
     */
    @Transactional
    public List<CommissionStep> moveToStep(Authentication auth, long commissionId, long stepId) {
        int owner = access.requirePro(auth);
        if (commissionId <= 0 || stepId <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Etapa inválida");
        lockCommission(owner, commissionId);
        String status = jdbc.queryForObject("""
            SELECT status FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=?
            """, String.class, owner, commissionId);
        if ("COMPLETED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Reabra o pedido antes de mudar a etapa de produção");
        }
        CommissionStep target = findOwned(owner, commissionId, stepId);
        // Every stage changes in one statement under a parent-row lock, so no
        // concurrent request can leave two active production stages.
        jdbc.update("""
            UPDATE creator_studio_commission_steps
               SET status=CASE WHEN position < ? THEN 'DONE'
                     WHEN position=? THEN 'IN_PROGRESS' ELSE 'PENDING' END,
                   completed_at=CASE WHEN position < ?
                     THEN COALESCE(completed_at,UTC_TIMESTAMP(6)) ELSE NULL END
             WHERE owner_user_id=? AND commission_id=?
            """, target.position(), target.position(), target.position(), owner, commissionId);
        jdbc.update("""
            UPDATE creator_studio_commissions
               SET updated_at=UTC_TIMESTAMP(6)
             WHERE owner_user_id=? AND id=?
            """, owner, commissionId);
        return jdbc.query("""
            SELECT id,commission_id,name,stage_definition_id,position,status,notes,completed_at
              FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=? ORDER BY position,id
            """, STEP_MAPPER, owner, commissionId);
    }

    @Transactional
    public void delete(Authentication auth, long commissionId, long stepId) {
        int owner = access.requirePro(auth);
        lockCommission(owner, commissionId);
        int changed = jdbc.update("""
            DELETE FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=? AND id=?
            """, owner, commissionId, stepId);
        if (changed != 1) throw notFound();
    }
}
