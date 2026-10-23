package com.Brafurries.API.user.recruitment;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** All records use Community, application, version and stage identity. */
@Repository
public class RecruitmentCandidateAttemptRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentCandidateAttemptRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant utc(LocalDateTime value) {
        return value==null?null:value.toInstant(ZoneOffset.UTC);
    }

    public record Policy(
        int maxAttempts,int timeLimitSeconds,Instant deadlineAt,
        boolean hardDeadline,Integer maxFocusLosses,boolean blockCopy
    ) {}
    public record DraftStage(
        long versionId,String processStatus,String versionStatus,
        String stageType,long revision
    ) {}
    public record Stage(
        long applicationId,long versionId,int candidateId,String applicationStatus,
        String stageStatus,String stageType,String formJson,Policy policy
    ) {}
    public record Attempt(
        long id,long applicationId,long stageId,int number,String status,
        Instant startedAt,Instant expiresAt,Instant submittedAt
    ) {}

    public DraftStage lockDraft(Integer communityId,long processId,long stageId) {
        var rows=jdbc.query(
            "SELECT v.id,p.status,v.status,s.stage_type,p.draft_revision "
                + "FROM recruitment_processes p "
                + "JOIN recruitment_process_versions v ON v.community_id=p.community_id "
                + "AND v.process_id=p.id "
                + "JOIN recruitment_stages s ON s.community_id=v.community_id "
                + "AND s.process_id=v.process_id AND s.process_version_id=v.id "
                + "WHERE p.community_id=? AND p.id=? AND s.id=? "
                + "ORDER BY v.version_number DESC LIMIT 1 FOR UPDATE",
            (rs,n)->new DraftStage(rs.getLong(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getLong(5)),
            communityId,processId,stageId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    public boolean savePolicy(
        Integer communityId,long processId,long stageId,long versionId,
        long expectedRevision,Policy policy
    ) {
        // Caller holds process/version row lock; this is one atomic draft revision update.
        if (jdbc.update(
            "UPDATE recruitment_processes SET draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId,processId,expectedRevision
        )!=1) return false;
        int changes=jdbc.update(
            "INSERT INTO recruitment_stage_attempt_policies "
                + "(community_id,process_version_id,stage_id,max_attempts,time_limit_seconds,"
                + "deadline_at,hard_deadline,max_focus_losses,block_copy) "
                + "VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE max_attempts=VALUES(max_attempts),"
                + "time_limit_seconds=VALUES(time_limit_seconds),"
                + "deadline_at=VALUES(deadline_at),"
                + "hard_deadline=VALUES(hard_deadline),"
                + "max_focus_losses=VALUES(max_focus_losses),"
                + "block_copy=VALUES(block_copy)",
            communityId,versionId,stageId,policy.maxAttempts(),
            policy.timeLimitSeconds(),
            policy.deadlineAt()==null?null:LocalDateTime.ofInstant(policy.deadlineAt(),ZoneOffset.UTC),
            policy.hardDeadline(),policy.maxFocusLosses(),policy.blockCopy()
        );
        return changes>=0;
    }

    public Policy policy(Integer communityId,long versionId,long stageId) {
        var list=jdbc.query(
            "SELECT max_attempts,time_limit_seconds,deadline_at,hard_deadline,"
                + "max_focus_losses,block_copy FROM recruitment_stage_attempt_policies "
                + "WHERE community_id=? AND process_version_id=? AND stage_id=?",
            (rs,n)->new Policy(rs.getInt(1),rs.getInt(2),
                utc(rs.getObject(3,LocalDateTime.class)),rs.getBoolean(4),
                (Integer)rs.getObject(5),rs.getBoolean(6)),
            communityId,versionId,stageId
        );
        return list.isEmpty()?null:list.get(0);
    }

    public Stage stage(Integer communityId,long applicationId,long stageId) {
        var rows=jdbc.query(
            "SELECT a.id,a.process_version_id,a.candidate_user_id,a.status,"
                + "ras.status,s.stage_type,s.candidate_form_json "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_application_stages ras "
                + "ON ras.community_id=a.community_id AND ras.application_id=a.id "
                + "AND ras.process_version_id=a.process_version_id "
                + "JOIN recruitment_stages s ON s.community_id=ras.community_id "
                + "AND s.process_version_id=ras.process_version_id AND s.id=ras.stage_id "
                + "JOIN recruitment_process_versions v "
                + "ON v.community_id=a.community_id AND v.process_id=a.process_id "
                + "AND v.id=a.process_version_id "
                + "JOIN recruitment_processes p "
                + "ON p.community_id=a.community_id AND p.id=a.process_id "
                + "WHERE a.community_id=? AND a.id=? AND s.id=? "
                + "AND v.status='PUBLISHED' AND p.status='ACTIVE'",
            (rs,n)->new Stage(rs.getLong(1),rs.getLong(2),rs.getInt(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getString(7),null),
            communityId,applicationId,stageId
        );
        if(rows.isEmpty()) return null;
        Stage row=rows.get(0);
        return new Stage(row.applicationId(),row.versionId(),row.candidateId(),
            row.applicationStatus(),row.stageStatus(),row.stageType(),row.formJson(),
            policy(communityId,row.versionId(),stageId));
    }

    public List<Attempt> attempts(Integer communityId,long applicationId,long stageId) {
        return jdbc.query(
            "SELECT id,application_id,stage_id,attempt_number,status,started_at,"
                + "expires_at,submitted_at FROM recruitment_candidate_attempts "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "ORDER BY attempt_number",
            (rs,n)->new Attempt(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getInt(4),rs.getString(5),
                utc(rs.getObject(6,LocalDateTime.class)),
                utc(rs.getObject(7,LocalDateTime.class)),
                utc(rs.getObject(8,LocalDateTime.class))),
            communityId,applicationId,stageId
        );
    }

    public Instant dbNow() {
        return jdbc.queryForObject(
            "SELECT UTC_TIMESTAMP(6)",
            (rs,n)->utc(rs.getObject(1,LocalDateTime.class))
        );
    }

    public Attempt create(
        Integer communityId,long applicationId,long versionId,long stageId,
        int number,Instant expiresAt
    ) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(conn->{
            var s=conn.prepareStatement(
                "INSERT INTO recruitment_candidate_attempts "
                    + "(community_id,application_id,process_version_id,stage_id,"
                    + "attempt_number,expires_at) "
                    + "VALUES (?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            s.setInt(1,communityId);s.setLong(2,applicationId);
            s.setLong(3,versionId);s.setLong(4,stageId);s.setInt(5,number);
            s.setObject(6,LocalDateTime.ofInstant(expiresAt,ZoneOffset.UTC));
            return s;
        },keys);
        if(keys.getKey()==null) throw new IllegalStateException("Tentativa sem ID");
        long id=keys.getKey().longValue();
        return attempts(communityId,applicationId,stageId).stream()
            .filter(a->a.id()==id).findFirst().orElseThrow();
    }

    public boolean expire(Integer communityId,long id) {
        return jdbc.update(
            "UPDATE recruitment_candidate_attempts SET status='EXPIRED' "
                + "WHERE community_id=? AND id=? AND status='STARTED'",
            communityId,id
        )==1;
    }

    public boolean submit(Integer communityId,long id,String answerJson) {
        return jdbc.update(
            "UPDATE recruitment_candidate_attempts SET status='SUBMITTED',"
                + "submitted_at=UTC_TIMESTAMP(6),answers_json=? "
                + "WHERE community_id=? AND id=? AND status='STARTED' "
                + "AND expires_at>=UTC_TIMESTAMP(6)",
            answerJson,communityId,id
        )==1;
    }
}
