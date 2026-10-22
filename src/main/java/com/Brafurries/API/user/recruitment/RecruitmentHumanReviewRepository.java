package com.Brafurries.API.user.recruitment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;

/** JDBC only: schema is owned by Database; all accesses repeat community_id. */
@Repository
public class RecruitmentHumanReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RecruitmentHumanReviewRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record StageIdentity(long stageId, long processId, long processVersionId, String type) {}
    public record Assignment(long id, long stageId, Integer userId, Integer roleId) {}
    public record AssessmentTarget(
        long stageId, long applicationId, long processVersionId,
        int candidateUserId, String stageStatus, String stageType, String staffFormJson
    ) {}
    public record Assessment(
        long id, long applicationId, long stageId, int evaluatorUserId,
        BigDecimal score, String opinion, JsonNode staffAnswers, Instant submittedAt
    ) {}

    public StageIdentity stage(Integer communityId, Long stageId) {
        var items = jdbc.query(
            "SELECT id,process_id,process_version_id,stage_type FROM recruitment_stages "
                + "WHERE community_id=? AND id=?",
            (rs, row) -> new StageIdentity(rs.getLong(1), rs.getLong(2),
                rs.getLong(3), rs.getString(4)), communityId, stageId
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public boolean eligiblePerson(Integer communityId, Integer userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_community_status WHERE community_id=? AND user_id=?",
            Integer.class, communityId, userId
        );
        return count != null && count > 0;
    }

    public boolean eligibleRole(Integer communityId, Integer roleId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM community_team_roles "
                + "WHERE community_id=? AND id=? AND active=1",
            Integer.class, communityId, roleId
        );
        return count != null && count > 0;
    }

    public List<Assignment> assignments(Integer communityId, long stageId) {
        return jdbc.query(
            "SELECT id,stage_id,user_id,role_id FROM recruitment_stage_reviewers "
                + "WHERE community_id=? AND stage_id=? ORDER BY id",
            (rs, row) -> new Assignment(rs.getLong(1), rs.getLong(2),
                (Integer) rs.getObject(3), (Integer) rs.getObject(4)),
            communityId, stageId
        );
    }

    public long assign(
        Integer communityId, StageIdentity stage,
        Integer userId, Integer roleId, Integer creatorId
    ) {
        var key = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var statement = conn.prepareStatement(
                "INSERT INTO recruitment_stage_reviewers "
                    + "(community_id,process_version_id,stage_id,user_id,role_id,created_by_user_id) "
                    + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS
            );
            statement.setInt(1, communityId);
            statement.setLong(2, stage.processVersionId());
            statement.setLong(3, stage.stageId());
            if (userId == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, userId);
            if (roleId == null) statement.setNull(5, java.sql.Types.INTEGER);
            else statement.setInt(5, roleId);
            statement.setInt(6, creatorId);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Designação sem ID");
        return key.getKey().longValue();
    }

    public boolean remove(Integer communityId, long stageId, long assignmentId) {
        return jdbc.update(
            "DELETE FROM recruitment_stage_reviewers WHERE community_id=? "
                + "AND stage_id=? AND id=?",
            communityId, stageId, assignmentId
        ) == 1;
    }

    public AssessmentTarget lockAssessmentTarget(
        Integer communityId, Long applicationId, Long stageId
    ) {
        var items = jdbc.query(
            "SELECT ras.stage_id,ras.application_id,ras.process_version_id,"
                + "a.candidate_user_id,ras.status,s.stage_type,s.staff_form_json "
                + "FROM recruitment_application_stages ras "
                + "JOIN recruitment_applications a ON a.community_id=ras.community_id "
                + "AND a.id=ras.application_id AND a.process_version_id=ras.process_version_id "
                + "JOIN recruitment_stages s ON s.community_id=ras.community_id "
                + "AND s.id=ras.stage_id AND s.process_version_id=ras.process_version_id "
                + "WHERE ras.community_id=? AND ras.application_id=? AND ras.stage_id=? FOR UPDATE",
            (rs, row) -> new AssessmentTarget(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getString(7)
            ), communityId, applicationId, stageId
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public boolean assignedToStage(
        Integer communityId, long stageId, long versionId, Integer reviewerId
    ) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_stage_reviewers sr "
                + "WHERE sr.community_id=? AND sr.stage_id=? AND sr.process_version_id=? "
                + "AND (sr.user_id=? OR EXISTS ("
                + "SELECT 1 FROM community_team_role_assignments x "
                + "JOIN community_team_roles r ON r.id=x.role_id "
                + "AND r.community_id=x.community_id AND r.active=1 "
                + "WHERE x.community_id=sr.community_id "
                + "AND x.role_id=sr.role_id AND x.user_id=?))",
            Integer.class, communityId, stageId, versionId, reviewerId, reviewerId
        );
        return count != null && count > 0;
    }

    public long submit(
        Integer communityId, long applicationId, long stageId, Integer reviewerId,
        BigDecimal score, String opinion, String staffAnswersJson
    ) {
        var key = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var statement = conn.prepareStatement(
                "INSERT INTO recruitment_stage_assessments "
                    + "(community_id,application_id,stage_id,evaluator_user_id,"
                    + "score,opinion,staff_answers_json) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            );
            statement.setInt(1, communityId);
            statement.setLong(2, applicationId);
            statement.setLong(3, stageId);
            statement.setInt(4, reviewerId);
            statement.setBigDecimal(5, score);
            statement.setString(6, opinion);
            statement.setString(7, staffAnswersJson);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Parecer sem ID");
        return key.getKey().longValue();
    }

    /** Only the assessment author may view this projection; no collective votes are disclosed. */
    public Assessment ownAssessment(
        Integer communityId, long applicationId, long stageId, Integer reviewerId
    ) {
        var items = jdbc.query(
            "SELECT id,application_id,stage_id,evaluator_user_id,score,opinion,"
                + "staff_answers_json,submitted_at FROM recruitment_stage_assessments "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "AND evaluator_user_id=?",
            (rs, row) -> new Assessment(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getBigDecimal(5), rs.getString(6), parse(rs.getString(7)),
                rs.getObject(8, LocalDateTime.class).toInstant(ZoneOffset.UTC)
            ), communityId, applicationId, stageId, reviewerId
        );
        return items.isEmpty() ? null : items.get(0);
    }

    private JsonNode parse(String source) {
        if (source == null) return null;
        try { return json.readTree(source); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Formulário interno persistido inválido", exception);
        }
    }
}
