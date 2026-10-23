package com.Brafurries.API.user.recruitment;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Strictly Community-scoped persistence. All stage state changes belong to one transaction. */
@Repository
public class RecruitmentStageDecisionRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentStageDecisionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Application(long id, long versionId, int candidateId, String status) {}
    public record Stage(long id, int position, String type, String status, String candidateFormJson) {}
    public record Decision(long id, long applicationId, long stageId, int decidedByUserId,
                           String outcome, String reason, Instant decidedAt) {}

    /** Application lock is the serialization point for simultaneous administrative decisions. */
    public Application lockApplication(Integer communityId, Long applicationId) {
        var rows = jdbc.query(
            "SELECT id,process_version_id,candidate_user_id,status "
                + "FROM recruitment_applications WHERE community_id=? AND id=? FOR UPDATE",
            (rs,n) -> new Application(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4)),
            communityId,applicationId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Avoid locking in the privileged decision GET path; no self-decision disclosure. */
    public Integer candidateId(Integer communityId,long applicationId) {
        var rows=jdbc.query(
            "SELECT candidate_user_id FROM recruitment_applications "
                + "WHERE community_id=? AND id=?",
            (rs,n)->rs.getInt(1),communityId,applicationId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    public List<Stage> stages(Integer communityId, long applicationId, long versionId) {
        return jdbc.query(
            "SELECT s.id,s.position,s.stage_type,ras.status,s.candidate_form_json "
                + "FROM recruitment_application_stages ras JOIN recruitment_stages s "
                + "ON s.community_id=ras.community_id "
                + "AND s.process_version_id=ras.process_version_id AND s.id=ras.stage_id "
                + "WHERE ras.community_id=? AND ras.application_id=? "
                + "AND ras.process_version_id=? ORDER BY s.position,s.id",
            (rs,n) -> new Stage(rs.getLong(1),rs.getInt(2),rs.getString(3),
                rs.getString(4),rs.getString(5)),
            communityId,applicationId,versionId
        );
    }

    /** Read the frozen policy only for this Community and stage of this version. */
    public String policyJson(Integer communityId,long stageId,long versionId) {
        var items = jdbc.query(
            "SELECT evaluation_config_json FROM recruitment_stages "
                + "WHERE community_id=? AND id=? AND process_version_id=?",
            (rs,n)->rs.getString(1),communityId,stageId,versionId
        );
        return items.isEmpty()? null:items.get(0);
    }

    public int assessmentCount(Integer communityId, long applicationId, long stageId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_stage_assessments "
                + "WHERE community_id=? AND application_id=? AND stage_id=?",
            Integer.class, communityId,applicationId,stageId
        );
        return count == null ? 0 : count;
    }

    public Decision decision(Integer communityId, long applicationId, long stageId) {
        var rows = jdbc.query(
            "SELECT id,application_id,stage_id,decided_by_user_id,outcome,reason,decided_at "
                + "FROM recruitment_stage_decisions "
                + "WHERE community_id=? AND application_id=? AND stage_id=?",
            (rs,n) -> new Decision(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getInt(4),
                rs.getString(5),rs.getString(6),
                rs.getObject(7,LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            communityId,applicationId,stageId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertDecision(Integer communityId, long applicationId, long stageId,
                               int userId, String outcome, String reason) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var stmt = conn.prepareStatement(
                "INSERT INTO recruitment_stage_decisions "
                    + "(community_id,application_id,stage_id,decided_by_user_id,outcome,reason) "
                    + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1,communityId); stmt.setLong(2,applicationId);
            stmt.setLong(3,stageId); stmt.setInt(4,userId);
            stmt.setString(5,outcome); stmt.setString(6,reason);
            return stmt;
        },keys);
        if (keys.getKey() == null) throw new IllegalStateException("Decisão sem ID");
        return keys.getKey().longValue();
    }

    /** Provenance is persisted separately; these decisions still belong to an accountable manager. */
    public long insertVoteDecision(
        Integer communityId,long applicationId,long stageId,
        int actorId,String outcome,String reason,String source
    ) {
        var generated=new GeneratedKeyHolder();
        jdbc.update(conn->{
            var s=conn.prepareStatement(
                "INSERT INTO recruitment_stage_decisions "
                    + "(community_id,application_id,stage_id,decided_by_user_id,"
                    + "outcome,reason,decision_source) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            s.setInt(1,communityId);s.setLong(2,applicationId);s.setLong(3,stageId);
            s.setInt(4,actorId);s.setString(5,outcome);s.setString(6,reason);
            s.setString(7,source);
            return s;
        },generated);
        if(generated.getKey()==null) throw new IllegalStateException("Decisão sem ID");
        return generated.getKey().longValue();
    }

    /** Objective decisions are manager-accountable, but score-determined and traceable. */
    public long insertObjectiveDecision(Integer communityId,long applicationId,long stageId,
        int actorId,String outcome,String reason,Long selectedAttemptId) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(conn->{
            var s=conn.prepareStatement(
                "INSERT INTO recruitment_stage_decisions "
                    + "(community_id,application_id,stage_id,decided_by_user_id,"
                    + "outcome,reason,decision_source,objective_attempt_id) "
                    + "VALUES (?,?,?,?,?,?,'OBJECTIVE_GRADE',?)",
                Statement.RETURN_GENERATED_KEYS);
            s.setInt(1,communityId);s.setLong(2,applicationId);
            s.setLong(3,stageId);s.setInt(4,actorId);
            s.setString(5,outcome);s.setString(6,reason);
            if(selectedAttemptId==null) s.setNull(7,java.sql.Types.BIGINT);
            else s.setLong(7,selectedAttemptId);
            return s;
        },keys);
        if(keys.getKey()==null) throw new IllegalStateException("Decisão objetiva sem ID");
        return keys.getKey().longValue();
    }

    /** Objective exams end from their candidate-facing status, not human-review status. */
    public boolean completeObjective(Integer communityId,long applicationId,long stageId) {
        return jdbc.update(
            "UPDATE recruitment_application_stages SET status='COMPLETED',"
                + "completed_at=UTC_TIMESTAMP(6) "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "AND status='AWAITING_CANDIDATE'",
            communityId,applicationId,stageId
        )==1;
    }

    public boolean complete(Integer communityId, long applicationId, long stageId) {
        return jdbc.update(
            "UPDATE recruitment_application_stages SET status='COMPLETED',"
                + "completed_at=UTC_TIMESTAMP(6) "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "AND status='AWAITING_REVIEW'",
            communityId,applicationId,stageId
        ) == 1;
    }

    public boolean advance(Integer communityId, long applicationId, long stageId, String status) {
        return jdbc.update(
            "UPDATE recruitment_application_stages SET status=?,"
                + "entered_at=UTC_TIMESTAMP(6) "
                + "WHERE community_id=? AND application_id=? AND stage_id=? AND status='PENDING'",
            status,communityId,applicationId,stageId
        ) == 1;
    }

    public int blockRemaining(Integer communityId, long applicationId) {
        return jdbc.update(
            "UPDATE recruitment_application_stages SET status='BLOCKED' "
                + "WHERE community_id=? AND application_id=? AND status='PENDING'",
            communityId,applicationId
        );
    }

    public boolean setApplicationStatus(Integer communityId, long applicationId, String status) {
        return jdbc.update(
            "UPDATE recruitment_applications SET status=? "
                + "WHERE community_id=? AND id=? AND status='IN_PROGRESS'",
            status,communityId,applicationId
        ) == 1;
    }
}
