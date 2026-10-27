package com.Brafurries.API.user.recruitment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Evidence and one-time reinstatement are keyed by the entire Community attempt. */
@Repository
public class RecruitmentExamReviewRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentExamReviewRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant utc(LocalDateTime time) {
        return time==null ? null : time.toInstant(ZoneOffset.UTC);
    }

    public record Incident(long attemptId,String status,int focusLosses,
        Instant originalExpiresAt,Instant reinstatedAt,Instant reinstatedUntil,
        Integer reviewedByUserId,String reviewReason) {}
    public record Signal(long id,String eventType,String source,Instant occurredAt) {}

    /** One candidate-initiated technical appeal per original blocked attempt. */
    public record Appeal(long attemptId,long applicationId,long stageId,int candidateUserId,
                         String reason,String status,Instant requestedAt,
                         Integer reviewedByUserId,String reviewReason,Instant reviewedAt) {}

    public Appeal appeal(Integer communityId,long applicationId,long stageId,long attemptId) {
        var values=jdbc.query(
            "SELECT attempt_id,application_id,stage_id,candidate_user_id,"
                + "reason,status,requested_at,reviewed_by_user_id,review_reason,reviewed_at "
                + "FROM recruitment_exam_block_appeals "
                + "WHERE community_id=? AND application_id=? AND stage_id=? AND attempt_id=?",
            (rs,n)->new Appeal(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getInt(4),
                rs.getString(5),rs.getString(6),utc(rs.getObject(7,LocalDateTime.class)),
                (Integer)rs.getObject(8),rs.getString(9),
                utc(rs.getObject(10,LocalDateTime.class))),
            communityId,applicationId,stageId,attemptId
        );
        return values.isEmpty()?null:values.get(0);
    }

    public boolean requestAppeal(Integer communityId,long applicationId,long stageId,
                                 long attemptId,int candidateId,String reason) {
        return jdbc.update(
            "INSERT INTO recruitment_exam_block_appeals "
                + "(community_id,attempt_id,application_id,stage_id,candidate_user_id,reason) "
                + "VALUES (?,?,?,?,?,?)",
            communityId,attemptId,applicationId,stageId,candidateId,reason
        )==1;
    }

    /** Must be called under the candidate application's FOR UPDATE lock. */
    public boolean resolveAppeal(Integer communityId,long applicationId,long stageId,
        long attemptId,int reviewerId,String status,String reason) {
        if(!"APPROVED".equals(status) && !"DENIED".equals(status))
            throw new IllegalArgumentException("Decisão de revisão inválida");
        return jdbc.update(
            "UPDATE recruitment_exam_block_appeals "
                + "SET status=?,reviewed_by_user_id=?,review_reason=?,"
                + "reviewed_at=UTC_TIMESTAMP(6) "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "AND attempt_id=? AND status='PENDING'",
            status,reviewerId,reason,communityId,applicationId,stageId,attemptId
        )==1;
    }

    public Incident incident(Integer communityId,long applicationId,long stageId,long attemptId) {
        var rows=jdbc.query(
            "SELECT a.id,a.status,a.focus_losses,COALESCE(r.original_expires_at,a.expires_at),"
                + "r.reinstated_at,r.expires_at,r.reviewed_by_user_id,r.reason "
                + "FROM recruitment_candidate_attempts a "
                + "LEFT JOIN recruitment_exam_reinstatements r "
                + "ON r.community_id=a.community_id AND r.attempt_id=a.id "
                + "WHERE a.community_id=? AND a.application_id=? AND a.stage_id=? AND a.id=?",
            (rs,n)->new Incident(rs.getLong(1),rs.getString(2),rs.getInt(3),
                utc(rs.getObject(4,LocalDateTime.class)),
                utc(rs.getObject(5,LocalDateTime.class)),
                utc(rs.getObject(6,LocalDateTime.class)),
                (Integer)rs.getObject(7),rs.getString(8)),
            communityId,applicationId,stageId,attemptId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    public List<Incident> incidents(Integer communityId,long applicationId,long stageId) {
        return jdbc.query(
            "SELECT a.id,a.status,a.focus_losses,COALESCE(r.original_expires_at,a.expires_at),"
                + "r.reinstated_at,r.expires_at,r.reviewed_by_user_id,r.reason "
                + "FROM recruitment_candidate_attempts a "
                + "LEFT JOIN recruitment_exam_reinstatements r "
                + "ON r.community_id=a.community_id AND r.attempt_id=a.id "
                + "WHERE a.community_id=? AND a.application_id=? AND a.stage_id=? "
                + "AND (a.status='BLOCKED' OR r.attempt_id IS NOT NULL) "
                + "ORDER BY a.attempt_number DESC LIMIT 5",
            (rs,n)->new Incident(rs.getLong(1),rs.getString(2),rs.getInt(3),
                utc(rs.getObject(4,LocalDateTime.class)),
                utc(rs.getObject(5,LocalDateTime.class)),
                utc(rs.getObject(6,LocalDateTime.class)),
                (Integer)rs.getObject(7),rs.getString(8)),
            communityId,applicationId,stageId
        );
    }

    /** Always bounded, latest-first; no clipboard data or user-entered content. */
    public List<Signal> signals(Integer communityId,long attemptId) {
        return jdbc.query(
            "SELECT id,event_type,source,occurred_at "
                + "FROM recruitment_exam_integrity_events "
                + "WHERE community_id=? AND attempt_id=? "
                + "ORDER BY id DESC LIMIT 101",
            (rs,n)->new Signal(rs.getLong(1),rs.getString(2),rs.getString(3),
                utc(rs.getObject(4,LocalDateTime.class))),
            communityId,attemptId
        );
    }

    public int focusSignals(Integer communityId,long attemptId) {
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_exam_integrity_events "
                + "WHERE community_id=? AND attempt_id=? AND event_type='FOCUS_LOST'",
            Integer.class,communityId,attemptId
        );
        return count==null?0:count;
    }

    public boolean reinstated(Integer communityId,long attemptId) {
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_exam_reinstatements "
                + "WHERE community_id=? AND attempt_id=?",
            Integer.class,communityId,attemptId
        );
        return count!=null && count>0;
    }

    /** Caller owns the application FOR UPDATE lock and validated the policy. */
    public void insertReinstatement(Integer communityId,long applicationId,long stageId,
        long attemptId,int actorId,String reason,int previousFocusLosses,Instant newExpiry) {
        if(jdbc.update(
            "INSERT INTO recruitment_exam_reinstatements "
                + "(community_id,application_id,stage_id,attempt_id,"
                + "reviewed_by_user_id,reason,previous_focus_losses,"
                + "original_started_at,original_expires_at,reinstated_at,expires_at) "
                + "SELECT a.community_id,a.application_id,a.stage_id,a.id,?,?,?,"
                + "a.started_at,a.expires_at,UTC_TIMESTAMP(6),? "
                + "FROM recruitment_candidate_attempts a "
                + "WHERE a.community_id=? AND a.application_id=? "
                + "AND a.stage_id=? AND a.id=? AND a.status='BLOCKED'",
            actorId,reason,previousFocusLosses,
            LocalDateTime.ofInstant(newExpiry,ZoneOffset.UTC),
            communityId,applicationId,stageId,attemptId
        )!=1) throw new IllegalStateException("Bloqueio original não encontrado para auditoria");
    }

    /** Reactivate the SAME attempt, with full fresh time. Historical events remain. */
    public boolean reopenBlocked(Integer communityId,long applicationId,long stageId,
        long attemptId,int expectedFocusLosses,Instant newExpiry) {
        return jdbc.update(
            "UPDATE recruitment_candidate_attempts "
                + "SET status='STARTED',focus_losses=0,"
                + "started_at=UTC_TIMESTAMP(6),expires_at=? "
                + "WHERE community_id=? AND application_id=? AND stage_id=? AND id=? "
                + "AND status='BLOCKED' AND focus_losses=?",
            LocalDateTime.ofInstant(newExpiry,ZoneOffset.UTC),
            communityId,applicationId,stageId,attemptId,expectedFocusLosses
        )==1;
    }
}
