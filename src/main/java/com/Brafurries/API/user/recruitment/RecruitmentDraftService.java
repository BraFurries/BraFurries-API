package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Draft-only API: publication, applications, assessments and votes are separate deliveries. */
@Service
public class RecruitmentDraftService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentDraftRepository drafts;
    private final CommunityAuditLogRepository audits;

    public RecruitmentDraftService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,
        RecruitmentDraftRepository drafts,
        CommunityAuditLogRepository audits
    ) {
        this.gate = gate;
        this.authorization = authorization;
        this.drafts = drafts;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public List<ProcessResponse> list(Authentication auth, Integer communityId) {
        requireManager(auth, communityId);
        return drafts.list(communityId);
    }

    @Transactional(readOnly = true)
    public ProcessDraftResponse detail(Authentication auth, Integer communityId, Long id) {
        requireManager(auth, communityId);
        ProcessResponse process = requireProcess(communityId, id);
        return new ProcessDraftResponse(process, drafts.stages(communityId, id));
    }

    @Transactional
    public ProcessResponse create(
        Authentication auth, Integer communityId, CreateProcessRequest request
    ) {
        var actor = requireManager(auth, communityId);
        String title = normalizedTitle(request.title());
        String description = normalizedDescription(request.description());
        Long id = drafts.create(communityId, actor.user().getId(), title, description);
        audit(actor, "RECRUITMENT_PROCESS_CREATED", id);
        return requireProcess(communityId, id);
    }

    @Transactional
    public ProcessResponse update(
        Authentication auth, Integer communityId, Long processId,
        UpdateProcessRequest request
    ) {
        var actor = requireManager(auth, communityId);
        String title = normalizedTitle(request.title());
        String description = normalizedDescription(request.description());
        if (!drafts.updateDraft(
            communityId, processId, title, description, requireRevision(request.expectedRevision())
        )) {
            ProcessResponse existing = drafts.find(communityId, processId);
            if (existing == null) {
                throw missing();
            }
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Rascunho desatualizado ou processo não editável"
            );
        }
        audit(actor, "RECRUITMENT_DRAFT_UPDATED", processId);
        return requireProcess(communityId, processId);
    }

    @Transactional
    public StageResponse appendStage(
        Authentication auth, Integer communityId, Long processId,
        CreateStageRequest request
    ) {
        var actor = requireManager(auth, communityId);
        String internalName = normalizedTitle(request.internalName());
        String publicName = normalizedTitle(request.publicName());
        if (request.stageType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de etapa obrigatório");
        }
        StageResponse stage;
        try {
            stage = drafts.appendDraftStage(
                communityId, processId, internalName, publicName, request.stageType(),
                requireRevision(request.expectedRevision())
            );
        } catch (OptimisticLockingFailureException exception) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Rascunho desatualizado. Recarregue antes de salvar", exception
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Conflito ao criar etapa no rascunho", exception
            );
        }
        if (stage == null) {
            ProcessResponse existing = drafts.find(communityId, processId);
            if (existing == null) {
                throw missing();
            }
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Nenhuma versão de rascunho disponível"
            );
        }
        audit(actor, "RECRUITMENT_STAGE_ADDED", stage.id());
        return stage;
    }

    private CommunityAuthorizationService.CommunityAccessContext requireManager(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        return authorization.requireCapability(
            auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        );
    }

    private ProcessResponse requireProcess(Integer communityId, Long id) {
        ProcessResponse process = drafts.find(communityId, id);
        if (process == null) {
            throw missing();
        }
        return process;
    }

    private Long requireRevision(Long revision) {
        if (revision == null || revision < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Revisão do rascunho obrigatória");
        }
        return revision;
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Processo não encontrado");
    }

    private String normalizedTitle(String input) {
        if (input == null || input.isBlank() || input.strip().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome inválido");
        }
        return input.strip();
    }

    private String normalizedDescription(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String description = input.strip();
        if (description.length() > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descrição excede o limite");
        }
        return description;
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor, String action, Long id
    ) {
        CommunityAuditLog record = new CommunityAuditLog();
        record.setCommunity(actor.community());
        record.setActorUser(actor.user());
        record.setAction(action);
        record.setTargetType("RECRUITMENT");
        record.setTargetId(String.valueOf(id));
        audits.save(record);
    }
}
