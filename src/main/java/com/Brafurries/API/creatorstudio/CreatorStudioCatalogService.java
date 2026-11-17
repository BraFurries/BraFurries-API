package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioProductionDtos.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
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
 * Artist-owned catalog only: production stages, reusable workflows, art types.
 * No schema changes, cross-Community rules, payment privileges or public data.
 */
@Service
public class CreatorStudioCatalogService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;

    public CreatorStudioCatalogService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    private static final RowMapper<ArtType> TYPE_MAPPER = (rs, n) ->
        new ArtType(rs.getLong("id"), rs.getString("name"), nullableLong(rs, "workflow_id"));
    private static final RowMapper<StageDefinition> STAGE_MAPPER = (rs, n) ->
        new StageDefinition(rs.getLong("id"), rs.getString("name"));
    private static final RowMapper<WorkflowStep> WORKFLOW_STEP_MAPPER = (rs, n) ->
        new WorkflowStep(rs.getLong("id"), rs.getString("name"),
            nullableLong(rs, "stage_definition_id"), rs.getInt("position"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String requiredName(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome inválido");
        }
        return raw.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Recurso não encontrado neste Studio");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Já existe um item com este nome ou organização");
    }

    private long insertName(String sql, String name, int owner) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, owner);
            ps.setString(2, name);
            return ps;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("ID não gerado");
        return keys.getKey().longValue();
    }

    private void requireOwnerRecord(String table, int owner, long id) {
        // table is exclusively a static server-owned identifier (never user input).
        if (!List.of("creator_studio_workflows","creator_studio_stage_definitions",
                "creator_studio_art_types").contains(table)) {
            throw new IllegalArgumentException("Tabela não permitida");
        }
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE owner_user_id=? AND id=?",
            Integer.class, owner, id);
        if (count == null || count != 1) throw notFound();
    }

    private void checkWorkflow(int owner, Long id) {
        if (id != null) {
            if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modelo inválido");
            requireOwnerRecord("creator_studio_workflows", owner, id);
        }
    }

    private void checkDefinition(int owner, Long id) {
        if (id != null) {
            if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Etapa inválida");
            requireOwnerRecord("creator_studio_stage_definitions", owner, id);
        }
    }

    @Transactional(readOnly = true)
    public List<ArtType> artTypes(Authentication auth) {
        int owner = access.requirePro(auth);
        return jdbc.query("""
            SELECT id,name,workflow_id FROM creator_studio_art_types
             WHERE owner_user_id=? ORDER BY name,id
            """, TYPE_MAPPER, owner);
    }

    @Transactional
    public ArtType createArtType(Authentication auth, ArtTypeDraft draft) {
        int owner = access.requirePro(auth);
        if (draft == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String name = requiredName(draft.name());
        checkWorkflow(owner, draft.workflowId());
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO creator_studio_art_types (owner_user_id,name,workflow_id)
                    VALUES (?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, owner);
                ps.setString(2, name);
                if (draft.workflowId() == null) ps.setNull(3, java.sql.Types.BIGINT);
                else ps.setLong(3, draft.workflowId());
                return ps;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("Tipo sem ID");
            return new ArtType(keys.getKey().longValue(), name, draft.workflowId());
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    @Transactional
    public ArtType updateArtType(Authentication auth, long id, ArtTypeDraft draft) {
        int owner = access.requirePro(auth);
        if (id <= 0 || draft == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String name = requiredName(draft.name());
        checkWorkflow(owner, draft.workflowId());
        requireOwnerRecord("creator_studio_art_types", owner, id);
        try {
            // MariaDB may report 0 changed rows for an idempotent PUT.
            jdbc.update("""
                UPDATE creator_studio_art_types SET name=?,workflow_id=?
                 WHERE id=? AND owner_user_id=?
                """, name, draft.workflowId(), id, owner);
            return new ArtType(id, name, draft.workflowId());
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    @Transactional(readOnly = true)
    public List<StageDefinition> stageDefinitions(Authentication auth) {
        int owner = access.requirePro(auth);
        return jdbc.query("""
            SELECT id,name FROM creator_studio_stage_definitions
             WHERE owner_user_id=? ORDER BY name,id
            """, STAGE_MAPPER, owner);
    }

    @Transactional
    public StageDefinition createStageDefinition(Authentication auth, StageDefinitionDraft draft) {
        int owner = access.requirePro(auth);
        String name = requiredName(draft == null ? null : draft.name());
        try {
            long id = insertName("""
                INSERT INTO creator_studio_stage_definitions (owner_user_id,name) VALUES (?,?)
                """, name, owner);
            return new StageDefinition(id, name);
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    private record WorkflowHeader(long id, String name, boolean isDefault) {}

    @Transactional(readOnly = true)
    public List<Workflow> workflows(Authentication auth) {
        int owner = access.requirePro(auth);
        List<WorkflowHeader> headers = jdbc.query("""
            SELECT id,name,is_default FROM creator_studio_workflows
             WHERE owner_user_id=? ORDER BY is_default DESC,name,id
            """, (rs, n) -> new WorkflowHeader(
                rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_default")), owner);
        // Do not issue a second JDBC query while the first ResultSet is open.
        return headers.stream()
            .map(h -> workflow(owner, h.id(), h.name(), h.isDefault()))
            .toList();
    }

    private Workflow workflow(int owner, long id, String name, boolean isDefault) {
        List<WorkflowStep> steps = jdbc.query("""
            SELECT id,name,stage_definition_id,position
              FROM creator_studio_workflow_steps
             WHERE owner_user_id=? AND workflow_id=?
             ORDER BY position,id
            """, WORKFLOW_STEP_MAPPER, owner, id);
        return new Workflow(id, name, isDefault, steps);
    }

    private void validateSteps(int owner, WorkflowDraft draft) {
        if (draft == null || draft.steps() == null || draft.steps().size() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modelo inválido");
        }
        requiredName(draft.name());
        for (StepDraft step : draft.steps()) {
            if (step == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Etapa inválida");
            }
            requiredName(step.name());
            checkDefinition(owner, step.stageDefinitionId());
        }
    }

    private void lockOwner(int owner) {
        // Serialize default-workflow changes and writes to the same artist's catalog.
        List<Integer> found = jdbc.query(
            "SELECT id FROM users WHERE id=? FOR UPDATE",
            (rs, n) -> rs.getInt(1), owner);
        if (found.isEmpty()) throw notFound();
    }

    @Transactional
    public Workflow createWorkflow(Authentication auth, WorkflowDraft draft) {
        int owner = access.requirePro(auth);
        validateSteps(owner, draft);
        lockOwner(owner);
        String name = requiredName(draft.name());
        try {
            if (draft.isDefault()) {
                jdbc.update("""
                    UPDATE creator_studio_workflows SET is_default=0
                     WHERE owner_user_id=? AND is_default=1
                    """, owner);
            }
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO creator_studio_workflows (owner_user_id,name,is_default)
                    VALUES (?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, owner);
                ps.setString(2, name);
                ps.setBoolean(3, draft.isDefault());
                return ps;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("Modelo sem ID");
            long id = keys.getKey().longValue();
            writeSteps(owner, id, draft.steps());
            return workflow(owner, id, name, draft.isDefault());
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    @Transactional
    public Workflow updateWorkflow(Authentication auth, long id, WorkflowDraft draft) {
        int owner = access.requirePro(auth);
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        validateSteps(owner, draft);
        lockOwner(owner);
        requireOwnerRecord("creator_studio_workflows", owner, id);
        String name = requiredName(draft.name());
        try {
            if (draft.isDefault()) {
                jdbc.update("""
                    UPDATE creator_studio_workflows SET is_default=0
                     WHERE owner_user_id=? AND id<>? AND is_default=1
                    """, owner, id);
            }
            // Ownership was checked above, so a zero changed-row count is not 404.
            jdbc.update("""
                UPDATE creator_studio_workflows
                   SET name=?,is_default=?,updated_at=UTC_TIMESTAMP(6)
                 WHERE id=? AND owner_user_id=?
                """, name, draft.isDefault(), id, owner);
            // Updating the reusable model never touches materialized commission steps.
            jdbc.update("""
                DELETE FROM creator_studio_workflow_steps
                 WHERE workflow_id=? AND owner_user_id=?
                """, id, owner);
            writeSteps(owner, id, draft.steps());
            return workflow(owner, id, name, draft.isDefault());
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    private void writeSteps(int owner, long workflowId, List<StepDraft> steps) {
        int position = 0;
        for (StepDraft item : steps) {
            jdbc.update("""
                INSERT INTO creator_studio_workflow_steps
                    (owner_user_id,workflow_id,stage_definition_id,name,position)
                VALUES (?,?,?,?,?)
                """, owner, workflowId, item.stageDefinitionId(),
                requiredName(item.name()), position++);
        }
    }
}
