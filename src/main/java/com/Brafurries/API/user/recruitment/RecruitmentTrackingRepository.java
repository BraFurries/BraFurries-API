package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentTrackingDtos.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only, tenant-scoped projections. No schema changes and no exposure of
 * staff_form_json, evaluation_config_json or objective answer keys.
 */
@Repository
public class RecruitmentTrackingRepository {
    private final JdbcTemplate jdbc;

    public RecruitmentTrackingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant utc(LocalDateTime date) {
        return date == null ? null : date.toInstant(ZoneOffset.UTC);
    }

    public List<OwnApplication> own(Integer communityId, Integer userId, Long beforeId) {
        return jdbc.query(
            "SELECT a.id,a.process_id,p.title,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.candidate_user_id=? "
                + "AND (? IS NULL OR a.id<?) "
                + "ORDER BY a.id DESC LIMIT 100",
            (rs, row) -> new OwnApplication(
                rs.getLong(1), rs.getLong(2), rs.getString(3),
                utc(rs.getObject(4, LocalDateTime.class)), "RECEIVED"
            ), communityId, userId, beforeId, beforeId
        );
    }

    public OwnApplication ownById(Integer communityId, Integer userId, Long applicationId) {
        List<OwnApplication> rows = jdbc.query(
            "SELECT a.id,a.process_id,p.title,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.candidate_user_id=? AND a.id=?",
            (rs, row) -> new OwnApplication(
                rs.getLong(1), rs.getLong(2), rs.getString(3),
                utc(rs.getObject(4, LocalDateTime.class)), "RECEIVED"
            ), communityId, userId, applicationId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ReviewQueueItem> queue(Integer communityId, Long processId, Integer reviewerId, Long beforeId) {
        return jdbc.query(
            "SELECT a.id,a.process_id,a.process_version_id,a.candidate_user_id,"
                + "p.title,a.status,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.process_id=? AND a.candidate_user_id<>? AND (? IS NULL OR a.id<?) "
                + "ORDER BY a.id DESC LIMIT 100",
            (rs, row) -> new ReviewQueueItem(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getString(5), rs.getString(6),
                utc(rs.getObject(7, LocalDateTime.class))
            ), communityId, processId, reviewerId, beforeId, beforeId
        );
    }

    public ReviewQueueItem reviewById(Integer communityId, Long applicationId) {
        List<ReviewQueueItem> rows = jdbc.query(
            "SELECT a.id,a.process_id,a.process_version_id,a.candidate_user_id,"
                + "p.title,a.status,a.submitted_at "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_processes p ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.id=?",
            (rs, row) -> new ReviewQueueItem(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getString(5), rs.getString(6),
                utc(rs.getObject(7, LocalDateTime.class))
            ), communityId, applicationId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ReviewStage> reviewStages(Integer communityId, Long applicationId) {
        return jdbc.query(
            "SELECT s.id,s.position,s.public_name,s.stage_type,ras.status,"
                + "ras.entered_at,ras.completed_at "
                + "FROM recruitment_application_stages ras "
                + "JOIN recruitment_applications a "
                + "ON a.community_id=ras.community_id "
                + "AND a.id=ras.application_id "
                + "AND a.process_version_id=ras.process_version_id "
                + "JOIN recruitment_stages s "
                + "ON s.community_id=ras.community_id "
                + "AND s.process_version_id=ras.process_version_id "
                + "AND s.id=ras.stage_id "
                + "WHERE ras.community_id=? AND ras.application_id=? "
                + "ORDER BY s.position,s.id",
            (rs, row) -> new ReviewStage(
                rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), utc(rs.getObject(6, LocalDateTime.class)),
                utc(rs.getObject(7, LocalDateTime.class))
            ), communityId, applicationId
        );
    }

    public record SubmittedAnswers(Long stageId, String json) {}

    public SubmittedAnswers candidateAnswers(Integer communityId, Long applicationId) {
        List<SubmittedAnswers> rows = jdbc.query(
            "SELECT raa.stage_id,raa.answers_json "
                + "FROM recruitment_application_answers raa "
                + "JOIN recruitment_applications a ON a.community_id=raa.community_id "
                + "AND a.id=raa.application_id "
                + "JOIN recruitment_application_stages ras "
                + "ON ras.community_id=raa.community_id "
                + "AND ras.application_id=raa.application_id "
                + "AND ras.stage_id=raa.stage_id "
                + "AND ras.process_version_id=a.process_version_id "
                + "JOIN recruitment_stages s "
                + "ON s.community_id=ras.community_id "
                + "AND s.process_version_id=ras.process_version_id "
                + "AND s.id=ras.stage_id AND s.position=1 AND s.stage_type='APPLICATION' "
                + "WHERE raa.community_id=? AND raa.application_id=?",
            (rs, row) -> new SubmittedAnswers(rs.getLong(1), rs.getString(2)),
            communityId, applicationId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
