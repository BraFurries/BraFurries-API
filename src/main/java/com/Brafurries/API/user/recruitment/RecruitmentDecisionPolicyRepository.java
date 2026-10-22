package com.Brafurries.API.user.recruitment;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecruitmentDecisionPolicyRepository {
    private final JdbcTemplate jdbc;
    public RecruitmentDecisionPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record StoredPolicy(
        long stageId, long processId, String stageType, String processStatus,
        String versionStatus, String policyJson, long draftRevision
    ) {}

    /** Administrative metadata; reads stay bound to Community AND process identity. */
    public StoredPolicy find(Integer communityId, Long processId, Long stageId) {
        var rows = jdbc.query(
            "SELECT s.id,s.process_id,s.stage_type,p.status,v.status,"
                + "s.evaluation_config_json,p.draft_revision "
                + "FROM recruitment_stages s JOIN recruitment_process_versions v "
                + "ON v.community_id=s.community_id AND v.process_id=s.process_id "
                + "AND v.id=s.process_version_id "
                + "JOIN recruitment_processes p "
                + "ON p.community_id=s.community_id AND p.id=s.process_id "
                + "WHERE s.community_id=? AND s.process_id=? AND s.id=?",
            (rs,n) -> new StoredPolicy(rs.getLong(1),rs.getLong(2),
                rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getLong(7)),
            communityId,processId,stageId
        );
        return rows.isEmpty()? null : rows.get(0);
    }

    /** Caller must be transactional. Process row lock serializes all draft changes. */
    public StoredPolicy update(
        Integer communityId, Long processId, Long stageId,
        Long expectedRevision, String serialized
    ) {
        var locked = jdbc.query(
            "SELECT id,status,draft_revision FROM recruitment_processes "
                + "WHERE community_id=? AND id=? FOR UPDATE",
            (rs,n) -> new Object[] {rs.getLong(1),rs.getString(2),rs.getLong(3)},
            communityId,processId
        );
        if (locked.isEmpty()) return null;
        Object[] process = locked.get(0);
        if (!"DRAFT".equals(process[1]) || ((Long)process[2]).longValue() != expectedRevision) {
            throw new OptimisticLockingFailureException("Rascunho desatualizado ou publicado");
        }
        var stage = find(communityId,processId,stageId);
        if (stage == null) return null;
        if (!"DRAFT".equals(stage.versionStatus())) {
            throw new OptimisticLockingFailureException("Versão publicada não é editável");
        }
        if ("AUTO_EVALUATION".equals(stage.stageType())) {
            throw new IllegalArgumentException("Etapa objetiva automática não aceita política humana");
        }
        if (jdbc.update(
            "UPDATE recruitment_processes SET draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId,processId,expectedRevision
        ) != 1) throw new OptimisticLockingFailureException("Rascunho alterado");
        if (jdbc.update(
            "UPDATE recruitment_stages SET evaluation_config_json=? "
                + "WHERE community_id=? AND process_id=? AND id=? "
                + "AND process_version_id IN "
                + "(SELECT id FROM recruitment_process_versions "
                + "WHERE community_id=? AND process_id=? AND status='DRAFT')",
            serialized,communityId,processId,stageId,communityId,processId
        ) != 1) {
            // A no-op update is still a valid, revised write if contents match.
            // The stage is guaranteed to exist and be draft under the parent lock.
            var refreshed = find(communityId,processId,stageId);
            if (refreshed == null || !"DRAFT".equals(refreshed.versionStatus()))
                throw new OptimisticLockingFailureException("Etapa não está mais disponível");
        }
        return find(communityId,processId,stageId);
    }
}
