package com.Brafurries.API.user.recruitment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Manager-only read model. Every lookup uses Community and process identity.
 * No form definitions, gabaritos or unrelated candidate records are projected.
 */
@Repository
public class RecruitmentDecisionDeskRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentDecisionDeskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    public record QueueItem(
        long id,long processId,long processVersionId,int candidateUserId,
        String processTitle,String internalStatus,Instant submittedAt
    ) {}

    public List<QueueItem> queue(
        Integer communityId,Long processId,Integer managerId,Long beforeId
    ) {
        return jdbc.query(
            "SELECT a.id,a.process_id,a.process_version_id,a.candidate_user_id,"
                + "p.title,a.status,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p "
                + "ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.process_id=? "
                + "AND a.candidate_user_id<>? AND (? IS NULL OR a.id<?) "
                + "ORDER BY a.id DESC LIMIT 100",
            (rs,n)->new QueueItem(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getInt(4),rs.getString(5),rs.getString(6),
                utc(rs.getObject(7,LocalDateTime.class))),
            communityId,processId,managerId,beforeId,beforeId
        );
    }

    public QueueItem application(Integer communityId,Long processId,Long applicationId) {
        var items=jdbc.query(
            "SELECT a.id,a.process_id,a.process_version_id,a.candidate_user_id,"
                + "p.title,a.status,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p "
                + "ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.process_id=? AND a.id=?",
            (rs,n)->new QueueItem(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getInt(4),rs.getString(5),rs.getString(6),
                utc(rs.getObject(7,LocalDateTime.class))),
            communityId,processId,applicationId
        );
        return items.isEmpty()?null:items.get(0);
    }

    public record StageRecord(
        long stageId,int position,String publicName,String stageType,
        String internalStatus,String policyJson,int assessmentCount,
        String decisionOutcome,String decisionReason,Integer decidedByUserId,
        Instant decidedAt
    ) {}

    /** All stage statuses are useful to manager; only current/completed stage evidence is available. */
    public List<StageRecord> stages(Integer communityId,long applicationId,long versionId) {
        return jdbc.query(
            "SELECT s.id,s.position,s.public_name,s.stage_type,ras.status,"
                + "s.evaluation_config_json,"
                + "(SELECT COUNT(*) FROM recruitment_stage_assessments r "
                + "WHERE r.community_id=ras.community_id "
                + "AND r.application_id=ras.application_id AND r.stage_id=ras.stage_id),"
                + "d.outcome,d.reason,d.decided_by_user_id,d.decided_at "
                + "FROM recruitment_application_stages ras "
                + "JOIN recruitment_stages s ON s.community_id=ras.community_id "
                + "AND s.id=ras.stage_id AND s.process_version_id=ras.process_version_id "
                + "LEFT JOIN recruitment_stage_decisions d "
                + "ON d.community_id=ras.community_id AND d.application_id=ras.application_id "
                + "AND d.stage_id=ras.stage_id "
                + "WHERE ras.community_id=? AND ras.application_id=? "
                + "AND ras.process_version_id=? ORDER BY s.position,s.id",
            (rs,n)->new StageRecord(rs.getLong(1),rs.getInt(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),
                rs.getString(8),rs.getString(9),(Integer)rs.getObject(10),
                utc(rs.getObject(11,LocalDateTime.class))),
            communityId,applicationId,versionId
        );
    }

    /** First-stage submitted candidate form only; no staff forms or answer keys. */
    public String applicationAnswers(Integer communityId,long applicationId,long versionId) {
        var rows=jdbc.query(
            "SELECT raa.answers_json FROM recruitment_application_answers raa "
                + "JOIN recruitment_application_stages ras "
                + "ON ras.community_id=raa.community_id AND ras.application_id=raa.application_id "
                + "AND ras.stage_id=raa.stage_id AND ras.process_version_id=? "
                + "JOIN recruitment_stages s "
                + "ON s.community_id=ras.community_id AND s.id=ras.stage_id "
                + "AND s.process_version_id=ras.process_version_id "
                + "AND s.position=1 AND s.stage_type='APPLICATION' "
                + "WHERE raa.community_id=? AND raa.application_id=? "
                + "AND ras.status IN ('IN_PROGRESS','AWAITING_REVIEW',"
                + "'AWAITING_PUBLICATION','COMPLETED')",
            (rs,n)->rs.getString(1),versionId,communityId,applicationId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    public record AssessmentRow(
        long id,int evaluatorUserId,BigDecimal score,String opinion,
        String staffAnswersJson,Instant submittedAt
    ) {}

    /** No evidence is reachable for a pending or blocked stage. */
    public List<AssessmentRow> evidence(
        Integer communityId,long applicationId,long versionId,long stageId
    ) {
        return jdbc.query(
            "SELECT assessment.id,assessment.evaluator_user_id,assessment.score,"
                + "assessment.opinion,assessment.staff_answers_json,assessment.submitted_at "
                + "FROM recruitment_stage_assessments assessment "
                + "JOIN recruitment_application_stages ras "
                + "ON ras.community_id=assessment.community_id "
                + "AND ras.application_id=assessment.application_id "
                + "AND ras.stage_id=assessment.stage_id "
                + "AND ras.process_version_id=? "
                + "WHERE assessment.community_id=? AND assessment.application_id=? "
                + "AND assessment.stage_id=? "
                + "AND ras.status IN ('IN_PROGRESS','AWAITING_REVIEW',"
                + "'AWAITING_PUBLICATION','COMPLETED') "
                + "ORDER BY assessment.submitted_at,assessment.id LIMIT 20",
            (rs,n)->new AssessmentRow(rs.getLong(1),rs.getInt(2),
                rs.getBigDecimal(3),rs.getString(4),rs.getString(5),
                utc(rs.getObject(6,LocalDateTime.class))),
            versionId,communityId,applicationId,stageId
        );
    }
}
