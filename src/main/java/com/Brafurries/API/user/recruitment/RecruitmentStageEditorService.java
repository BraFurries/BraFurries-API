package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentStageEditorDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecruitmentStageEditorService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentDraftRepository processes;
    private final RecruitmentStageEditorRepository stages;
    private final RecruitmentFormValidator forms;
    private final CommunityAuditLogRepository audits;

    public RecruitmentStageEditorService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,
        RecruitmentDraftRepository processes,
        RecruitmentStageEditorRepository stages,
        RecruitmentFormValidator forms,
        CommunityAuditLogRepository audits
    ) {
        this.gate = gate;
        this.authorization = authorization;
        this.processes = processes;
        this.stages = stages;
        this.forms = forms;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public StageDefinitionResponse detail(
        Authentication authentication, Integer communityId, Long processId, Long stageId
    ) {
        authorize(authentication, communityId);
        StageDefinitionResponse stage = stages.find(communityId, processId, stageId);
        if (stage == null) throw notFound();
        return stage;
    }

    @Transactional
    public StageDefinitionResponse update(
        Authentication authentication, Integer communityId, Long processId,
        Long stageId, SaveStageRequest request
    ) {
        var actor = authorize(authentication, communityId);
        if (request == null || request.stageType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de etapa obrigatório");
        }
        String internalName = title(request.internalName());
        String publicName = title(request.publicName());

        var candidate = forms.validate(request.candidateForm(), false, request.stageType());
        var staff = forms.validate(request.staffForm(), true, request.stageType());
        var validated = new SaveStageRequest(
            internalName, publicName, request.stageType(), candidate, staff
        );
        StageDefinitionResponse updated = stages.save(
            communityId, processId, stageId, validated
        );
        if (updated == null) throw missingOrImmutable(communityId, processId);
        audit(actor, "RECRUITMENT_STAGE_UPDATED", stageId);
        return updated;
    }

    @Transactional
    public List<RecruitmentDraftDtos.StageResponse> reorder(
        Authentication authentication, Integer communityId, Long processId,
        ReorderStagesRequest request
    ) {
        var actor = authorize(authentication, communityId);
        if (request == null || request.stageIds() == null
            || request.stageIds().size() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ordem de etapas inválida");
        }
        try {
            if (!stages.reorder(communityId, processId, request.stageIds())) {
                throw missingOrImmutable(communityId, processId);
            }
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        audit(actor, "RECRUITMENT_STAGES_REORDERED", processId);
        return processes.stages(communityId, processId);
    }

    @Transactional
    public void delete(
        Authentication authentication, Integer communityId, Long processId, Long stageId
    ) {
        var actor = authorize(authentication, communityId);
        try {
            if (!stages.delete(communityId, processId, stageId)) {
                throw missingOrImmutable(communityId, processId);
            }
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Etapa já utilizada não pode ser removida", conflict);
        }
        audit(actor, "RECRUITMENT_STAGE_REMOVED", stageId);
    }

    private CommunityAuthorizationService.CommunityAccessContext authorize(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        return authorization.requireCapability(
            auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        );
    }

    private String title(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome de etapa inválido");
        }
        return value.strip();
    }

    private ResponseStatusException missingOrImmutable(Integer communityId, Long processId) {
        var process = processes.find(communityId, processId);
        if (process == null) return notFound();
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Processo ou versão não é mais um rascunho editável, ou etapa inexistente");
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa não encontrada");
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action, Long targetId
    ) {
        CommunityAuditLog log = new CommunityAuditLog();
        log.setCommunity(actor.community());
        log.setActorUser(actor.user());
        log.setAction(action);
        log.setTargetType("RECRUITMENT");
        log.setTargetId(targetId.toString());
        audits.save(log);
    }
}
