package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType;
import static com.Brafurries.API.user.recruitment.RecruitmentStageEditorDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Draft-only SQL; schema and migrations belong exclusively to Database.
 * Caller must run every mutation in a transaction. Process row lock serializes
 * all writer operations including append, reorder and deletion.
 */
@Repository
public class RecruitmentStageEditorRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RecruitmentStageEditorRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public StageDefinitionResponse find(Integer communityId, Long processId, Long stageId) {
        var rows = jdbc.query(
            "SELECT s.id,s.process_id,s.process_version_id,s.position,"
                + "s.internal_name,s.public_name,s.stage_type,"
                + "s.candidate_form_json,s.staff_form_json "
                + "FROM recruitment_stages s JOIN recruitment_process_versions v "
                + "ON v.community_id=s.community_id AND v.process_id=s.process_id "
                + "AND v.id=s.process_version_id "
                + "JOIN recruitment_processes p "
                + "ON p.community_id=s.community_id AND p.id=s.process_id "
                + "WHERE s.community_id=? AND s.process_id=? AND s.id=? "
                + "AND p.status='DRAFT' AND v.status='DRAFT'",
            (rs, idx) -> new StageDefinitionResponse(
                rs.getLong("id"), rs.getLong("process_id"),
                rs.getLong("process_version_id"), rs.getInt("position"),
                rs.getString("internal_name"), rs.getString("public_name"),
                StageType.valueOf(rs.getString("stage_type")),
                readForm(rs.getString("candidate_form_json")),
                readForm(rs.getString("staff_form_json"))
            ),
            communityId, processId, stageId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StageDefinitionResponse save(
        Integer communityId, Long processId, Long stageId, SaveStageRequest definition
    ) {
        Long version = lockDraftVersion(communityId, processId);
        if (version == null) return null;

        int changed = jdbc.update(
            "UPDATE recruitment_stages SET internal_name=?,public_name=?,"
                + "stage_type=?,candidate_form_json=?,staff_form_json=? "
                + "WHERE community_id=? AND process_id=? AND process_version_id=? AND id=?",
            definition.internalName(), definition.publicName(), definition.stageType().name(),
            writeForm(definition.candidateForm()), writeForm(definition.staffForm()),
            communityId, processId, version, stageId
        );
        return changed == 1 ? find(communityId, processId, stageId) : null;
    }

    /**
     * Exact permutation only. Two-pass position shifting avoids unique-key
     * collisions on MariaDB even for swaps (1 -> 2, 2 -> 1).
     */
    public boolean reorder(Integer communityId, Long processId, List<Long> order) {
        Long version = lockDraftVersion(communityId, processId);
        if (version == null) return false;
        List<Long> existing = stageIds(communityId, version);
        if (existing.size() != order.size()
            || order.contains(null)
            || new HashSet<>(order).size() != order.size()
            || !Set.copyOf(existing).equals(Set.copyOf(order))) {
            throw new IllegalArgumentException("Ordem deve conter cada etapa exatamente uma vez");
        }
        reorderVersion(communityId, version, order);
        return true;
    }

    public boolean delete(Integer communityId, Long processId, Long stageId) {
        Long version = lockDraftVersion(communityId, processId);
        if (version == null) return false;
        int deleted = jdbc.update(
            "DELETE FROM recruitment_stages WHERE community_id=? "
                + "AND process_id=? AND process_version_id=? AND id=?",
            communityId, processId, version, stageId
        );
        if (deleted != 1) return false;
        reorderVersion(communityId, version, stageIds(communityId, version));
        return true;
    }

    /** Locks the parent for serializable writer order under normal READ COMMITTED/REPEATABLE READ. */
    private Long lockDraftVersion(Integer communityId, Long processId) {
        var process = jdbc.query(
            "SELECT id FROM recruitment_processes "
                + "WHERE community_id=? AND id=? AND status='DRAFT' FOR UPDATE",
            (rs, row) -> rs.getLong(1), communityId, processId
        );
        if (process.isEmpty()) return null;
        var versions = jdbc.query(
            "SELECT id FROM recruitment_process_versions "
                + "WHERE community_id=? AND process_id=? AND status='DRAFT' "
                + "ORDER BY version_number DESC LIMIT 1 FOR UPDATE",
            (rs, row) -> rs.getLong(1), communityId, processId
        );
        return versions.isEmpty() ? null : versions.get(0);
    }

    private List<Long> stageIds(Integer communityId, Long versionId) {
        return jdbc.query(
            "SELECT id FROM recruitment_stages "
                + "WHERE community_id=? AND process_version_id=? ORDER BY position,id",
            (rs, row) -> rs.getLong(1), communityId, versionId
        );
    }

    private void reorderVersion(Integer communityId, Long versionId, List<Long> order) {
        if (order.isEmpty()) return;
        Long currentMax = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position),0) FROM recruitment_stages "
                + "WHERE community_id=? AND process_version_id=?",
            Long.class, communityId, versionId
        );
        long offset = currentMax == null ? 0L : currentMax;
        if (offset + order.size() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Excesso de etapas na versão");
        }
        for (int i = 0; i < order.size(); i++) {
            jdbc.update(
                "UPDATE recruitment_stages SET position=? "
                    + "WHERE community_id=? AND process_version_id=? AND id=?",
                offset + i + 1, communityId, versionId, order.get(i)
            );
        }
        for (int i = 0; i < order.size(); i++) {
            jdbc.update(
                "UPDATE recruitment_stages SET position=? "
                    + "WHERE community_id=? AND process_version_id=? AND id=?",
                i + 1, communityId, versionId, order.get(i)
            );
        }
    }

    private String writeForm(RecruitmentFormDefinition form) {
        if (form == null) return null;
        try {
            return json.writeValueAsString(form);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Formulário inválido", exception);
        }
    }

    private RecruitmentFormDefinition readForm(String stored) {
        if (stored == null) return null;
        try {
            return json.readValue(stored, RecruitmentFormDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Definição de formulário persistida inválida", exception);
        }
    }
}
