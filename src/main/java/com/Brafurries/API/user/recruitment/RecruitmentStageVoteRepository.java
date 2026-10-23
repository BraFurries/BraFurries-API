package com.Brafurries.API.user.recruitment;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Ballot and aggregate reads are strictly tenant- and stage-scoped. */
@Repository
public class RecruitmentStageVoteRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentStageVoteRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Ballot(
        long id,long applicationId,long stageId,int voterUserId,String choice,Instant castAt
    ) {}
    public record Tally(int approves,int rejects) {
        public int count() { return approves+rejects; }
        public boolean tied() { return approves==rejects; }
        public String majority() { return approves>rejects?"APPROVE":"REJECT"; }
    }

    public Ballot own(Integer communityId,long applicationId,long stageId,int voterId) {
        var rows=jdbc.query(
            "SELECT id,application_id,stage_id,voter_user_id,choice,cast_at "
                + "FROM recruitment_stage_votes WHERE community_id=? "
                + "AND application_id=? AND stage_id=? AND voter_user_id=?",
            (rs,n)->new Ballot(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getInt(4),rs.getString(5),
                rs.getObject(6,LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            communityId,applicationId,stageId,voterId
        );
        return rows.isEmpty()?null:rows.get(0);
    }

    public long cast(Integer communityId,long applicationId,long stageId,int voterId,String choice) {
        var generated=new GeneratedKeyHolder();
        jdbc.update(conn->{
            var s=conn.prepareStatement(
                "INSERT INTO recruitment_stage_votes "
                    + "(community_id,application_id,stage_id,voter_user_id,choice) "
                    + "VALUES (?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            s.setInt(1,communityId);s.setLong(2,applicationId);s.setLong(3,stageId);
            s.setInt(4,voterId);s.setString(5,choice);
            return s;
        },generated);
        if (generated.getKey()==null) throw new IllegalStateException("Voto sem ID");
        return generated.getKey().longValue();
    }

    /**
     * Current-read rows under FOR UPDATE. InnoDB REPEATABLE READ's earlier
     * authorization SELECT may have established an old transaction snapshot:
     * a plain aggregate could miss committed ballots even after the
     * application serialization lock has been acquired.
     */
    public Tally lockedTally(Integer communityId,long applicationId,long stageId) {
        var choices=jdbc.query(
            "SELECT choice FROM recruitment_stage_votes "
                + "WHERE community_id=? AND application_id=? AND stage_id=? FOR UPDATE",
            (rs,n)->rs.getString(1),communityId,applicationId,stageId
        );
        int yes=0,no=0;
        for (String choice:choices) {
            if ("APPROVE".equals(choice)) yes++;
            else if ("REJECT".equals(choice)) no++;
            else throw new IllegalStateException("Voto persistido inválido");
        }
        return new Tally(yes,no);
    }

    public Tally tally(Integer communityId,long applicationId,long stageId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN choice='APPROVE' THEN 1 ELSE 0 END),0), "
                + "COALESCE(SUM(CASE WHEN choice='REJECT' THEN 1 ELSE 0 END),0) "
                + "FROM recruitment_stage_votes "
                + "WHERE community_id=? AND application_id=? AND stage_id=?",
            (rs,n)->new Tally(rs.getInt(1),rs.getInt(2)),
            communityId,applicationId,stageId
        );
    }
}
