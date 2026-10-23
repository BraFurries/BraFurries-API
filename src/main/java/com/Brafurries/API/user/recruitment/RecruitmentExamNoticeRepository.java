package com.Brafurries.API.user.recruitment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Private, tenant-qualified records; notifications never contain grades or answer keys. */
@Repository
public class RecruitmentExamNoticeRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentExamNoticeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Notice(long id,long applicationId,long stageId,long attemptId,
        String eventType,Instant occurredAt,Instant newExpiresAt,boolean read) {}

    private static Instant utc(LocalDateTime v) {
        return v==null?null:v.toInstant(ZoneOffset.UTC);
    }

    /** Called only after a successful transition in the surrounding transaction. */
    public void record(Integer communityId,long applicationId,long stageId,long attemptId,
        String type,Instant newExpiry) {
        if(jdbc.update(
            "INSERT INTO recruitment_exam_incident_notices "
                +"(community_id,application_id,stage_id,attempt_id,event_type,"
                +"occurred_at,new_expires_at) "
                +"VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),?)",
            communityId,applicationId,stageId,attemptId,type,
            newExpiry==null?null:LocalDateTime.ofInstant(newExpiry,ZoneOffset.UTC))!=1)
            throw new IllegalStateException("Aviso de incidente não persistido");
    }

    public List<Notice> candidate(Integer communityId,long applicationId,int userId) {
        return jdbc.query(
            "SELECT n.id,n.application_id,n.stage_id,n.attempt_id,n.event_type,"
                +"n.occurred_at,n.new_expires_at,(r.notice_id IS NOT NULL) "
                +"FROM recruitment_exam_incident_notices n "
                +"JOIN recruitment_applications a ON a.community_id=n.community_id "
                +"AND a.id=n.application_id "
                +"LEFT JOIN recruitment_exam_notice_reads r "
                +"ON r.community_id=n.community_id AND r.notice_id=n.id AND r.user_id=? "
                +"WHERE n.community_id=? AND n.application_id=? AND a.candidate_user_id=? "
                +"ORDER BY n.id DESC LIMIT 50",
            (rs,row)->new Notice(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getString(5),
                utc(rs.getObject(6,LocalDateTime.class)),
                utc(rs.getObject(7,LocalDateTime.class)),rs.getBoolean(8)),
            userId,communityId,applicationId,userId);
    }

    public List<Notice> staff(Integer communityId,long processId,int userId) {
        return jdbc.query(
            "SELECT n.id,n.application_id,n.stage_id,n.attempt_id,n.event_type,"
                +"n.occurred_at,n.new_expires_at,(r.notice_id IS NOT NULL) "
                +"FROM recruitment_exam_incident_notices n "
                +"JOIN recruitment_applications a ON a.community_id=n.community_id "
                +"AND a.id=n.application_id "
                +"LEFT JOIN recruitment_exam_notice_reads r "
                +"ON r.community_id=n.community_id AND r.notice_id=n.id AND r.user_id=? "
                +"WHERE n.community_id=? AND a.process_id=? AND a.candidate_user_id<>? "
                +"ORDER BY n.id DESC LIMIT 50",
            (rs,row)->new Notice(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getString(5),
                utc(rs.getObject(6,LocalDateTime.class)),
                utc(rs.getObject(7,LocalDateTime.class)),rs.getBoolean(8)),
            userId,communityId,processId,userId);
    }

    public boolean candidateNoticeExists(Integer communityId,long applicationId,
        long noticeId,int userId) {
        Integer found=jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_exam_incident_notices n "
                +"JOIN recruitment_applications a ON a.community_id=n.community_id "
                +"AND a.id=n.application_id "
                +"WHERE n.community_id=? AND n.application_id=? AND n.id=? "
                +"AND a.candidate_user_id=?",
            Integer.class,communityId,applicationId,noticeId,userId);
        return found!=null && found==1;
    }

    public boolean staffNoticeExists(Integer communityId,long processId,
        long noticeId,int userId) {
        Integer found=jdbc.queryForObject(
            "SELECT COUNT(*) FROM recruitment_exam_incident_notices n "
                +"JOIN recruitment_applications a ON a.community_id=n.community_id "
                +"AND a.id=n.application_id "
                +"WHERE n.community_id=? AND a.process_id=? AND n.id=? "
                +"AND a.candidate_user_id<>?",
            Integer.class,communityId,processId,noticeId,userId);
        return found!=null && found==1;
    }

    /** Insert-only acknowledgement, idempotent per Community/notice/reader. */
    public void markRead(Integer communityId,long noticeId,int userId) {
        jdbc.update(
            "INSERT INTO recruitment_exam_notice_reads "
                +"(community_id,notice_id,user_id,read_at) "
                +"VALUES (?,?,?,UTC_TIMESTAMP(6)) "
                +"ON DUPLICATE KEY UPDATE read_at=read_at",
            communityId,noticeId,userId);
    }
}
