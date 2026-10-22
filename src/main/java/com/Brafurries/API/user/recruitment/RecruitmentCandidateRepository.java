package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentCandidateDtos.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.Statement;
import org.springframework.stereotype.Repository;

/**
 * Strictly tenant-scoped queries. Only public, currently OPEN selections are
 * candidate-readable; all private staff JSON columns are excluded.
 */
@Repository
public class RecruitmentCandidateRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentCandidateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CandidateSelection(
        long processId, String title, String description, Instant closesAt,
        long versionId, String eligibilityJson, String termsJson,
        long firstStageId, String candidateFormJson
    ) {}

    public List<OpenProcess> listOpen(Integer communityId) {
        return jdbc.query(
            "SELECT p.id,p.title,p.description,p.enrollment_ends_at "
                + "FROM recruitment_processes p WHERE p.community_id=? "
                + "AND p.status='ACTIVE' AND p.visibility='PUBLIC' "
                + "AND p.enrollment_status='OPEN' "
                + "AND p.enrollment_starts_at <= UTC_TIMESTAMP(6) "
                + "AND p.enrollment_ends_at > UTC_TIMESTAMP(6) "
                + "AND EXISTS (SELECT 1 FROM recruitment_process_versions v "
                + "WHERE v.community_id=p.community_id AND v.process_id=p.id "
                + "AND v.status='PUBLISHED') ORDER BY p.id DESC LIMIT 100",
            (rs,row)->new OpenProcess(rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC)), communityId
        );
    }

    public CandidateSelection publishedSelection(
        Integer communityId, Long processId, boolean requireOpen, boolean forUpdate
    ) {
        String sql =
            "SELECT p.id,p.title,p.description,p.enrollment_ends_at,"
                + "v.id AS version_id,v.eligibility_rules_json,v.terms_json,"
                + "s.id AS stage_id,s.candidate_form_json "
                + "FROM recruitment_processes p "
                + "JOIN recruitment_process_versions v "
                + "ON v.community_id=p.community_id AND v.process_id=p.id "
                + "JOIN recruitment_stages s "
                + "ON s.community_id=v.community_id AND s.process_id=v.process_id "
                + "AND s.process_version_id=v.id AND s.position=1 "
                + "AND s.stage_type='APPLICATION' "
                + "WHERE p.community_id=? AND p.id=? AND p.status='ACTIVE' "
                + "AND v.status='PUBLISHED' "
                + (requireOpen
                    ? "AND p.visibility='PUBLIC' AND p.enrollment_status='OPEN' "
                        + "AND p.enrollment_starts_at<=UTC_TIMESTAMP(6) "
                        + "AND p.enrollment_ends_at>UTC_TIMESTAMP(6) "
                    : "")
                + "ORDER BY v.version_number DESC LIMIT 1"
                + (forUpdate ? " FOR UPDATE" : "");
        var results = jdbc.query(sql, (rs,row)->new CandidateSelection(
                rs.getLong("id"),rs.getString("title"),rs.getString("description"),
                rs.getObject("enrollment_ends_at",LocalDateTime.class) == null
                    ? null
                    : rs.getObject("enrollment_ends_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getLong("version_id"),rs.getString("eligibility_rules_json"),
                rs.getString("terms_json"),rs.getLong("stage_id"),
                rs.getString("candidate_form_json")),
            communityId, processId);
        return results.isEmpty()?null:results.get(0);
    }

    public List<Long> stageIds(Integer communityId, Long processId, Long versionId) {
        return jdbc.query(
            "SELECT id FROM recruitment_stages WHERE community_id=? AND process_id=? "
                + "AND process_version_id=? ORDER BY position,id",
            (rs,row)->rs.getLong(1), communityId,processId,versionId);
    }

    public Applied submit(
        Integer communityId, long processId, long versionId, long candidateId,
        long applicationStageId, List<Long> stages, String answerJson, String termsHash
    ) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var stmt = conn.prepareStatement(
                "INSERT INTO recruitment_applications "
                    + "(community_id,process_id,process_version_id,candidate_user_id,"
                    + "terms_accepted_at,terms_sha256) "
                    + "VALUES (?,?,?,?,UTC_TIMESTAMP(6),?)", Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1,communityId);stmt.setLong(2,processId);stmt.setLong(3,versionId);
            stmt.setLong(4,candidateId);stmt.setString(5,termsHash);return stmt;
        },key);
        if (key.getKey()==null) throw new IllegalStateException("Candidatura sem identificador");
        long id = key.getKey().longValue();
        for (long stageId : stages) {
            jdbc.update(
                "INSERT INTO recruitment_application_stages "
                    + "(community_id,application_id,process_version_id,stage_id,status,entered_at) "
                    + "VALUES (?,?,?,?,?,CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE NULL END)",
                communityId,id,versionId,stageId,
                stageId == applicationStageId ? "AWAITING_REVIEW" : "PENDING",
                stageId == applicationStageId
            );
        }
        jdbc.update(
            "INSERT INTO recruitment_application_answers "
                + "(community_id,application_id,stage_id,answers_json) VALUES (?,?,?,?)",
            communityId,id,applicationStageId,answerJson
        );
        return new Applied(id,versionId,"IN_PROGRESS",Instant.now());
    }

    public boolean openEnrollment(
        Integer communityId, long processId, LocalDateTime closesAtUtc,
        long expectedRevision
    ) {
        return jdbc.update(
            "UPDATE recruitment_processes SET visibility='PUBLIC', "
                + "draft_revision=draft_revision+1, "
                + "enrollment_status='OPEN',enrollment_starts_at=UTC_TIMESTAMP(6), "
                + "enrollment_ends_at=? WHERE community_id=? AND id=? "
                + "AND status='ACTIVE' AND visibility='PRIVATE' AND enrollment_status='CLOSED' "
                + "AND draft_revision=?",
            closesAtUtc,communityId,processId,expectedRevision
        )==1;
    }

    public boolean closeEnrollment(Integer communityId, long processId) {
        return jdbc.update(
            "UPDATE recruitment_processes SET enrollment_status='CLOSED' "
                + "WHERE community_id=? AND id=? AND status='ACTIVE' "
                + "AND enrollment_status='OPEN'",
            communityId,processId
        )==1;
    }
}
