package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
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
 * Global library is a platform resource, NOT an artist, Community or commerce
 * tenant. Only platform owner can publish/edit. Pro artists may read and
 * explicitly COPY a published snapshot into their private workflow catalog.
 */
@Service
public class CreatorStudioGlobalWorkflowService {
    public record GlobalStep(long id, String name, int position) {}
    public record GlobalWorkflow(long id, String name, String description,
                                 boolean active, List<GlobalStep> steps) {}
    public record GlobalWorkflowDraft(String name, String description,
                                      boolean active, List<String> steps) {}
    public record ImportDraft(boolean setAsDefault) {}

    private record Header(long id, String name, String description, boolean active) {}

    private static final RowMapper<Header> HEADER_MAPPER = (rs, row) ->
        new Header(rs.getLong("id"), rs.getString("name"),
            rs.getString("description"), rs.getBoolean("active"));
    private static final RowMapper<GlobalStep> STEP_MAPPER = (rs, row) ->
        new GlobalStep(rs.getLong("id"), rs.getString("name"), rs.getInt("position"));

    private final CreatorStudioAccessService access;
    private final PlatformAuthorizationService platform;
    private final CreatorStudioCatalogService artistCatalog;
    private final JdbcTemplate jdbc;

    public CreatorStudioGlobalWorkflowService(CreatorStudioAccessService access,
            PlatformAuthorizationService platform, CreatorStudioCatalogService artistCatalog,
            JdbcTemplate jdbc) {
        this.access = access;
        this.platform = platform;
        this.artistCatalog = artistCatalog;
        this.jdbc = jdbc;
    }

    private int requireOwner(Authentication auth) {
        User owner = platform.requireOwner(auth);
        if (owner.getId() == null || owner.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return owner.getId();
    }

    private static String requiredName(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Nome de modelo ou etapa inválido");
        }
        return raw.trim();
    }

    private static GlobalWorkflowDraft validated(GlobalWorkflowDraft raw) {
        if (raw == null || raw.steps() == null || raw.steps().isEmpty()
                || raw.steps().size() > 40 ||
                (raw.description() != null && raw.description().trim().length() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Modelo deve conter de 1 a 40 etapas e descrição de até 500 caracteres");
        }
        String name = requiredName(raw.name());
        List<String> steps = raw.steps().stream().map(step -> requiredName(step)).toList();
        return new GlobalWorkflowDraft(name,
            raw.description() == null ? "" : raw.description().trim(), raw.active(), steps);
    }

    private GlobalWorkflow toWorkflow(Header header) {
        List<GlobalStep> steps = jdbc.query("""
            SELECT id,name,position FROM creator_studio_global_workflow_steps
             WHERE workflow_id=? ORDER BY position,id
            """, STEP_MAPPER, header.id());
        return new GlobalWorkflow(header.id(), header.name(), header.description(),
            header.active(), steps);
    }

    @Transactional(readOnly = true)
    public List<GlobalWorkflow> published(Authentication auth) {
        access.requirePro(auth);
        List<Header> headers = jdbc.query("""
            SELECT id,name,description,active FROM creator_studio_global_workflows
             WHERE active=1 ORDER BY name,id
            """, HEADER_MAPPER);
        return headers.stream().map(this::toWorkflow).toList();
    }

    @Transactional(readOnly = true)
    public List<GlobalWorkflow> administrative(Authentication auth) {
        requireOwner(auth);
        List<Header> headers = jdbc.query("""
            SELECT id,name,description,active FROM creator_studio_global_workflows
             ORDER BY active DESC,name,id
            """, HEADER_MAPPER);
        return headers.stream().map(this::toWorkflow).toList();
    }

    @Transactional
    public GlobalWorkflow create(Authentication auth, GlobalWorkflowDraft draft) {
        int actor = requireOwner(auth);
        GlobalWorkflowDraft safe = validated(draft);
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO creator_studio_global_workflows
                        (name,description,active,created_by_user_id,updated_by_user_id)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, safe.name());
                ps.setString(2, safe.description());
                ps.setBoolean(3, safe.active());
                ps.setInt(4, actor);
                ps.setInt(5, actor);
                return ps;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("Global model without ID");
            long id = keys.getKey().longValue();
            writeSteps(id, safe.steps());
            return new GlobalWorkflow(id, safe.name(), safe.description(), safe.active(),
                stepsOf(id));
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Já existe um modelo global com este nome");
        }
    }

    @Transactional
    public GlobalWorkflow update(Authentication auth, long id, GlobalWorkflowDraft draft) {
        int actor = requireOwner(auth);
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        GlobalWorkflowDraft safe = validated(draft);
        // Lock template before changing its steps; import takes the same lock.
        List<Long> found = jdbc.query("""
            SELECT id FROM creator_studio_global_workflows WHERE id=? FOR UPDATE
            """, (rs, i) -> rs.getLong("id"), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Modelo global não encontrado");
        try {
            jdbc.update("""
                UPDATE creator_studio_global_workflows
                   SET name=?,description=?,active=?,updated_by_user_id=?,
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE id=?
                """, safe.name(), safe.description(), safe.active(), actor, id);
            jdbc.update("DELETE FROM creator_studio_global_workflow_steps WHERE workflow_id=?", id);
            writeSteps(id, safe.steps());
            return new GlobalWorkflow(id, safe.name(), safe.description(), safe.active(),
                stepsOf(id));
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Já existe um modelo global com este nome");
        }
    }

    private void writeSteps(long id, List<String> steps) {
        int position = 0;
        for (String name : steps) {
            jdbc.update("""
                INSERT INTO creator_studio_global_workflow_steps
                    (workflow_id,name,position) VALUES (?,?,?)
                """, id, name, position++);
        }
    }

    private List<GlobalStep> stepsOf(long id) {
        return jdbc.query("""
            SELECT id,name,position FROM creator_studio_global_workflow_steps
             WHERE workflow_id=? ORDER BY position,id
            """, STEP_MAPPER, id);
    }

    @Transactional
    public CreatorStudioProductionDtos.Workflow importAsPersonal(
            Authentication auth, long globalId, ImportDraft draft) {
        int artist = access.requirePro(auth);
        if (globalId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // Writer and importer serialize on global parent, not an artist table.
        List<Header> headers = jdbc.query("""
            SELECT id,name,description,active FROM creator_studio_global_workflows
             WHERE id=? AND active=1 FOR UPDATE
            """, HEADER_MAPPER, globalId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Modelo global indisponível");
        List<GlobalStep> steps = stepsOf(globalId);
        if (steps.isEmpty() || steps.size() > 40) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Modelo global sem etapas válidas");
        }
        // Personal copy has NO global FK and NO cross-user stage_definition_id.
        // A later edit/deactivation cannot rewrite an artist's private history.
        return artistCatalog.createWorkflow(auth, new CreatorStudioProductionDtos.WorkflowDraft(
            headers.get(0).name(), draft != null && draft.setAsDefault(),
            steps.stream().map(step ->
                new CreatorStudioProductionDtos.StepDraft(null,step.name())).toList()
        ));
    }
}
