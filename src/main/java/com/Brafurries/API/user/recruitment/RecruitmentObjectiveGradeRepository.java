package com.Brafurries.API.user.recruitment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only typed DML; Database/Flyway is the schema authority. */
@Repository
public class RecruitmentObjectiveGradeRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentObjectiveGradeRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record GradePolicy(BigDecimal passingPercentage,String resultStrategy) {}
    public record Grade(long attemptId,int earnedPoints,int possiblePoints,
        int correctQuestions,int totalQuestions,BigDecimal passingPercentage,
        boolean passed,Instant gradedAt) {}
    public record StageRef(long versionId,int candidateId,String type) {}

    /** Null means grading is not yet configured for this frozen stage. */
    public GradePolicy policy(Integer communityId,long versionId,long stageId) {
        var rows=jdbc.query(
            "SELECT passing_percentage,result_strategy "
                + "FROM recruitment_stage_attempt_policies "
                + "WHERE community_id=? AND process_version_id=? AND stage_id=? "
                + "AND passing_percentage IS NOT NULL AND result_strategy IS NOT NULL",
            (rs,n)->new GradePolicy(rs.getBigDecimal(1),rs.getString(2)),
            communityId,versionId,stageId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    /** Caller holds process row lock; a failed update rolls back the transaction. */
    public boolean configure(Integer communityId,long processId,long versionId,long stageId,
        long revision,GradePolicy policy) {
        if(jdbc.update(
            "UPDATE recruitment_processes SET draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId,processId,revision
        )!=1) return false;
        if(jdbc.update(
            "UPDATE recruitment_stage_attempt_policies "
                + "SET passing_percentage=?,result_strategy=? "
                + "WHERE community_id=? AND process_version_id=? AND stage_id=?",
            policy.passingPercentage(),policy.resultStrategy(),
            communityId,versionId,stageId
        )!=1) {
            throw new IllegalStateException("Política de tentativas não existe nesta versão");
        }
        return true;
    }

    public void insertGrade(Integer communityId,long attemptId,
        RecruitmentObjectiveScorer.Score score,BigDecimal passPercentage) {
        jdbc.update(
            "INSERT INTO recruitment_candidate_attempt_grades "
                + "(community_id,attempt_id,earned_points,possible_points,"
                + "correct_questions,total_questions,passing_percentage,passed) "
                + "VALUES (?,?,?,?,?,?,?,?)",
            communityId,attemptId,score.earnedPoints(),score.possiblePoints(),
            score.correctQuestions(),score.totalQuestions(),passPercentage,score.passed()
        );
    }

    public List<Grade> grades(Integer communityId,long applicationId,long stageId) {
        return loadGrades(communityId,applicationId,stageId,false);
    }

    /** Current-read grades, not a repeatable-read snapshot from before the app lock. */
    public List<Grade> gradesLocked(Integer communityId,long applicationId,long stageId) {
        return loadGrades(communityId,applicationId,stageId,true);
    }

    private List<Grade> loadGrades(Integer communityId,long applicationId,long stageId,
        boolean currentRead) {
        return jdbc.query(
            "SELECT g.attempt_id,g.earned_points,g.possible_points,"
                + "g.correct_questions,g.total_questions,g.passing_percentage,"
                + "g.passed,g.graded_at "
                + "FROM recruitment_candidate_attempt_grades g "
                + "JOIN recruitment_candidate_attempts a "
                + "ON a.community_id=g.community_id AND a.id=g.attempt_id "
                + "WHERE a.community_id=? AND a.application_id=? AND a.stage_id=? "
                + "AND a.status='SUBMITTED' "
                + "ORDER BY a.attempt_number"
                + (currentRead ? " FOR UPDATE" : ""),
            (rs,n)->new Grade(rs.getLong(1),rs.getInt(2),rs.getInt(3),
                rs.getInt(4),rs.getInt(5),rs.getBigDecimal(6),
                rs.getBoolean(7),rs.getObject(8,LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            communityId,applicationId,stageId
        );
    }

    /** Admin display status only, never candidate disclosure. */
    public boolean finalized(Integer communityId,long applicationId,long stageId) {
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_stage_decisions "
                + "WHERE community_id=? AND application_id=? AND stage_id=? "
                + "AND decision_source='OBJECTIVE_GRADE'",
            Integer.class,communityId,applicationId,stageId
        );
        return count!=null && count>0;
    }

    /** Tenant- and process-version-scoped management lookup. */
    public StageRef stage(Integer communityId,long applicationId,long stageId) {
        var rows=jdbc.query(
            "SELECT a.process_version_id,a.candidate_user_id,s.stage_type "
                + "FROM recruitment_applications a "
                + "JOIN recruitment_application_stages ras "
                + "ON ras.community_id=a.community_id AND ras.application_id=a.id "
                + "AND ras.process_version_id=a.process_version_id AND ras.stage_id=? "
                + "JOIN recruitment_stages s ON s.community_id=ras.community_id "
                + "AND s.process_version_id=ras.process_version_id AND s.id=ras.stage_id "
                + "WHERE a.community_id=? AND a.id=?",
            (rs,n)->new StageRef(rs.getLong(1),rs.getInt(2),rs.getString(3)),
            stageId,communityId,applicationId
        );
        return rows.isEmpty()?null:rows.get(0);
    }
}
