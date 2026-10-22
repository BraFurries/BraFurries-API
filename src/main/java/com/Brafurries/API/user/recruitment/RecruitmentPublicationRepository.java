package com.Brafurries.API.user.recruitment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The database is schema authority; this repository issues only tenant-scoped DML. */
@Repository
public class RecruitmentPublicationRepository {
    private final JdbcTemplate jdbc;

    public RecruitmentPublicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DraftProcess(Long id, long revision, String status) {}
    public record DraftVersion(Long id, String status, String eligibilityJson, String termsJson) {
        public DraftVersion(Long id, String status, String eligibilityJson) {
            this(id, status, eligibilityJson, null);
        }
    }
    public record DraftStage(
        Long id, int position, String stageType,
        String candidateFormJson, String staffFormJson
    ) {}

    private static final RowMapper<DraftProcess> PROCESS_MAPPER = (rs, row) ->
        new DraftProcess(rs.getLong("id"), rs.getLong("draft_revision"), rs.getString("status"));

    public DraftProcess lockProcess(Integer communityId, Long processId) {
        List<DraftProcess> rows = jdbc.query(
            "SELECT id,draft_revision,status FROM recruitment_processes "
                + "WHERE community_id=? AND id=? FOR UPDATE",
            PROCESS_MAPPER, communityId, processId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public DraftProcess findProcess(Integer communityId, Long processId) {
        List<DraftProcess> rows = jdbc.query(
            "SELECT id,draft_revision,status FROM recruitment_processes "
                + "WHERE community_id=? AND id=?",
            PROCESS_MAPPER, communityId, processId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DraftVersion> drafts(Integer communityId, Long processId) {
        return jdbc.query(
            "SELECT id,status,eligibility_rules_json,terms_json FROM recruitment_process_versions "
                + "WHERE community_id=? AND process_id=? AND status='DRAFT' "
                + "ORDER BY version_number DESC LIMIT 2",
            (rs, row) -> new DraftVersion(
                rs.getLong("id"), rs.getString("status"),
                rs.getString("eligibility_rules_json"), rs.getString("terms_json")
            ), communityId, processId
        );
    }

    public List<DraftStage> stages(Integer communityId, Long processId, Long versionId) {
        return jdbc.query(
            "SELECT id,position,stage_type,candidate_form_json,staff_form_json "
                + "FROM recruitment_stages WHERE community_id=? AND process_id=? "
                + "AND process_version_id=? ORDER BY position,id",
            (rs, row) -> new DraftStage(
                rs.getLong("id"), rs.getInt("position"), rs.getString("stage_type"),
                rs.getString("candidate_form_json"), rs.getString("staff_form_json")
            ), communityId, processId, versionId
        );
    }

    public boolean updateEligibility(
        Integer communityId, Long processId, Long versionId,
        long expectedRevision, String eligibilityJson
    ) {
        int changed = jdbc.update(
            "UPDATE recruitment_processes SET draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId, processId, expectedRevision
        );
        if (changed != 1) return false;
        if (jdbc.update(
            "UPDATE recruitment_process_versions SET eligibility_rules_json=? "
                + "WHERE community_id=? AND process_id=? AND id=? AND status='DRAFT'",
            eligibilityJson, communityId, processId, versionId
        ) != 1) {
            throw new IllegalStateException("A versão do rascunho mudou durante a atualização");
        }
        return true;
    }

    public boolean updateTerms(
        Integer communityId, Long processId, Long versionId,
        long expectedRevision, String termsJson
    ) {
        if (jdbc.update(
            "UPDATE recruitment_processes SET draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId, processId, expectedRevision
        ) != 1) return false;
        if (jdbc.update(
            "UPDATE recruitment_process_versions SET terms_json=? "
                + "WHERE community_id=? AND process_id=? AND id=? AND status='DRAFT'",
            termsJson, communityId, processId, versionId
        ) != 1) throw new IllegalStateException("A versão mudou durante a gravação dos termos");
        return true;
    }

    /**
     * Atomic publication under caller's @Transactional and process FOR UPDATE.
     * Zero rows is a concurrency error; API rolls back the entire transaction.
     * Intake is intentionally CLOSED and visibility PRIVATE.
     */
    public boolean publish(
        Integer communityId, Long processId, Long versionId, long expectedRevision
    ) {
        if (jdbc.update(
            "UPDATE recruitment_process_versions "
                + "SET status='PUBLISHED',published_at=UTC_TIMESTAMP(6) "
                + "WHERE community_id=? AND process_id=? AND id=? AND status='DRAFT'",
            communityId, processId, versionId
        ) != 1) return false;

        int changed = jdbc.update(
            "UPDATE recruitment_processes SET status='ACTIVE', "
                + "visibility='PRIVATE',enrollment_status='CLOSED', "
                + "draft_revision=draft_revision+1 "
                + "WHERE community_id=? AND id=? AND status='DRAFT' AND draft_revision=?",
            communityId, processId, expectedRevision
        );
        if (changed != 1) {
            throw new IllegalStateException("Processo alterado durante a publicação");
        }
        return true;
    }
}
