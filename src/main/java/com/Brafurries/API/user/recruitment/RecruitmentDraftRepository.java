package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * SQL access for the draft-only recruitment foundation.
 * Always filters by community_id. No schema mutation is performed in the API.
 *
 * Requires Database V20261021_001; controllers are disabled until explicitly
 * enabled for the approved Community after the DB migration is applied.
 */
@Repository
public class RecruitmentDraftRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<ProcessResponse> PROCESS_MAPPER = (rs, row) ->
        new ProcessResponse(
            rs.getLong("id"),
            rs.getInt("community_id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("visibility"),
            rs.getString("enrollment_status"),
            rs.getInt("draft_version")
        );

    private static final RowMapper<StageResponse> STAGE_MAPPER = (rs, row) ->
        new StageResponse(
            rs.getLong("id"),
            rs.getLong("process_id"),
            rs.getLong("process_version_id"),
            rs.getInt("position"),
            rs.getString("internal_name"),
            rs.getString("public_name"),
            StageType.valueOf(rs.getString("stage_type"))
        );

    public RecruitmentDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProcessResponse> list(Integer communityId) {
        return jdbc.query(
            "SELECT p.id,p.community_id,p.title,p.description,p.status,p.visibility,"
                + "p.enrollment_status,COALESCE(MAX(v.version_number),0) AS draft_version "
                + "FROM recruitment_processes p "
                + "LEFT JOIN recruitment_process_versions v "
                + "ON v.community_id=p.community_id AND v.process_id=p.id "
                + "AND v.status='DRAFT' "
                + "WHERE p.community_id=? "
                + "GROUP BY p.id,p.community_id,p.title,p.description,p.status,"
                + "p.visibility,p.enrollment_status "
                + "ORDER BY p.id DESC",
            PROCESS_MAPPER,
            communityId
        );
    }

    public ProcessResponse find(Integer communityId, Long processId) {
        List<ProcessResponse> values = jdbc.query(
            "SELECT p.id,p.community_id,p.title,p.description,p.status,p.visibility,"
                + "p.enrollment_status,COALESCE(MAX(v.version_number),0) AS draft_version "
                + "FROM recruitment_processes p "
                + "LEFT JOIN recruitment_process_versions v "
                + "ON v.community_id=p.community_id AND v.process_id=p.id "
                + "AND v.status='DRAFT' "
                + "WHERE p.community_id=? AND p.id=? "
                + "GROUP BY p.id,p.community_id,p.title,p.description,p.status,"
                + "p.visibility,p.enrollment_status",
            PROCESS_MAPPER, communityId, processId
        );
        return values.isEmpty() ? null : values.get(0);
    }

    public Long create(Integer communityId, Integer creatorId, String title, String description) {
        Long processId = insert(
            "INSERT INTO recruitment_processes "
                + "(community_id,title,description,created_by_user_id) VALUES (?,?,?,?)",
            communityId, title, description, creatorId
        );
        insert(
            "INSERT INTO recruitment_process_versions "
                + "(community_id,process_id,version_number,created_by_user_id) "
                + "VALUES (?,?,1,?)",
            communityId, processId, creatorId
        );
        return processId;
    }

    /** Returns false when the process does not exist or is no longer a draft. */
    public boolean updateDraft(Integer communityId, Long processId, String title, String description) {
        return jdbc.update(
            "UPDATE recruitment_processes SET title=?,description=? "
                + "WHERE community_id=? AND id=? AND status='DRAFT'",
            title, description, communityId, processId
        ) == 1;
    }

    public List<StageResponse> stages(Integer communityId, Long processId) {
        return jdbc.query(
            "SELECT s.id,s.process_id,s.process_version_id,s.position,"
                + "s.internal_name,s.public_name,s.stage_type "
                + "FROM recruitment_stages s "
                + "JOIN recruitment_process_versions v "
                + "ON v.community_id=s.community_id AND v.process_id=s.process_id "
                + "AND v.id=s.process_version_id "
                + "WHERE s.community_id=? AND s.process_id=? AND v.status='DRAFT' "
                + "ORDER BY s.position,s.id",
            STAGE_MAPPER, communityId, processId
        );
    }

    /**
     * Locks the draft process row to serialize position allocation. Published
     * or missing processes are not eligible for stage changes.
     */
    public StageResponse appendDraftStage(
        Integer communityId, Long processId, String internalName,
        String publicName, StageType stageType
    ) {
        List<Long> locked = jdbc.query(
            "SELECT id FROM recruitment_processes "
                + "WHERE community_id=? AND id=? AND status='DRAFT' FOR UPDATE",
            (rs, row) -> rs.getLong(1), communityId, processId
        );
        if (locked.isEmpty()) {
            return null;
        }
        List<Long> drafts = jdbc.query(
            "SELECT id FROM recruitment_process_versions "
                + "WHERE community_id=? AND process_id=? AND status='DRAFT' "
                + "ORDER BY version_number DESC LIMIT 1",
            (rs, row) -> rs.getLong(1), communityId, processId
        );
        if (drafts.isEmpty()) {
            return null;
        }
        long versionId = drafts.get(0);
        Integer position = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position),0)+1 FROM recruitment_stages "
                + "WHERE community_id=? AND process_version_id=?",
            Integer.class, communityId, versionId
        );
        if (position == null || position < 1) {
            throw new DataIntegrityViolationException("Invalid next stage position");
        }
        Long id = insert(
            "INSERT INTO recruitment_stages "
                + "(community_id,process_id,process_version_id,position,"
                + "internal_name,public_name,stage_type) VALUES (?,?,?,?,?,?,?)",
            communityId, processId, versionId, position,
            internalName, publicName, stageType.name()
        );
        return new StageResponse(id, processId, versionId, position,
            internalName, publicName, stageType);
    }

    private Long insert(String sql, Object... parameters) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS
            );
            for (int i = 0; i < parameters.length; i++) {
                Object value = parameters[i];
                if (value == null) {
                    statement.setNull(i + 1, Types.VARCHAR);
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) {
            throw new DataIntegrityViolationException("No generated recruitment ID");
        }
        return id.longValue();
    }
}
