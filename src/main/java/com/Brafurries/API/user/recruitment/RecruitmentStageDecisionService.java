package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit V1 administrative decision, NOT collective voting or automatic grading.
 * Decision is private: neither this service nor tracking publishes candidate outcomes.
 */
@Service
public class RecruitmentStageDecisionService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentStageDecisionRepository repository;
    private final CommunityAuditLogRepository audits;

    public RecruitmentStageDecisionService(
        RecruitmentFeatureGate gate, CommunityAuthorizationService authorization,
        RecruitmentStageDecisionRepository repository, CommunityAuditLogRepository audits
    ) {
        this.gate = gate;
        this.authorization = authorization;
        this.repository = repository;
        this.audits = audits;
    }

    public record DecisionRequest(String outcome, String reason) {}
    public record DecisionReceipt(
        Decision decision, String applicationStatus, Long nextStageId, String nextStageStatus
    ) {}

    @Transactional(readOnly = true)
    public Decision find(Authentication auth, Integer communityId, Long applicationId, Long stageId) {
        manager(auth,communityId);
        requireIds(applicationId,stageId);
        var decision = repository.decision(communityId,applicationId,stageId);
        if (decision == null) throw missing();
        return decision;
    }

    @Transactional
    public DecisionReceipt decide(
        Authentication auth, Integer communityId, Long applicationId,
        Long stageId, DecisionRequest request
    ) {
        var actor = manager(auth,communityId);
        requireIds(applicationId,stageId);
        if (request == null || request.outcome() == null ||\n            !List.of("APPROVE","REJECT").contains(request.outcome())) {
            throw invalid("Decisão deve ser APPROVE ou REJECT");
        }
        String reason = request.reason() == null ? null : request.reason().strip();
        if (reason != null && reason.isEmpty()) reason = null;
        if (reason != null && reason.length() > 2000) throw invalid("Justificativa muito longa");
        if ("REJECT".equals(request.outcome()) && (reason == null || reason.length() < 10)) {
            throw invalid("Reprovação exige justificativa de pelo menos 10 caracteres");
        }

        // Every administrative transition serializes through the candidate application row.
        var application = repository.lockApplication(communityId,applicationId);
        if (application == null) throw missing();
        if (application.candidateId() == actor.user().getId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Não pode decidir sua candidatura");
        }
        if (!"IN_PROGRESS".equals(application.status())) {
            throw conflict("A candidatura não está mais em avaliação");
        }
        var stages = repository.stages(communityId,applicationId,application.versionId());
        int selected = -1;
        for (int i=0; i<stages.size(); i++) {
            if (stages.get(i).id() == stageId) { selected=i; break; }
        }
        if (selected < 0) throw missing();
        var current = stages.get(selected);
        if ("AUTO_EVALUATION".equals(current.type())) {
            throw conflict("Etapa automática não aceita decisão humana manual neste recorte");
        }
        if (!"AWAITING_REVIEW".equals(current.status())) {
            throw conflict("Esta etapa não está aguardando decisão humana");
        }
        for (int i=0; i<stages.size(); i++) {
            if (i < selected && !"COMPLETED".equals(stages.get(i).status())) {
                throw conflict("Existem etapas anteriores não concluídas");
            }
            if (i > selected && !"PENDING".equals(stages.get(i).status())) {
                throw conflict("Existe etapa posterior já iniciada");
            }
        }
        // For now, one or more individual assessments are mandatory. No implicit
        // quorum, averaging, ranking or grade-to-decision mapping is introduced.
        if (repository.assessmentCount(communityId,applicationId,stageId) < 1) {
            throw conflict("A etapa precisa de pelo menos um parecer humano registrado");
        }
        if (repository.decision(communityId,applicationId,stageId) != null) {
            throw conflict("Esta etapa já possui uma decisão");
        }

        try {
            repository.insertDecision(communityId,applicationId,stageId,actor.user().getId(),
                request.outcome(),reason);
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("Esta etapa já possui uma decisão");
        }
        if (!repository.complete(communityId,applicationId,stageId)) {
            throw conflict("Etapa modificada concorrentemente");
        }

        Long nextId = null;
        String nextStatus = null;
        String applicationStatus = "IN_PROGRESS";
        if ("REJECT".equals(request.outcome())) {
            repository.blockRemaining(communityId,applicationId);
            applicationStatus = "AWAITING_PUBLICATION";
        } else if (selected == stages.size()-1) {
            applicationStatus = "AWAITING_PUBLICATION";
        } else {
            var next = stages.get(selected+1);
            nextId = next.id();
            // Candidate-answer and automatic stages MUST NOT silently skip their
            // candidate work; subsequent endpoints will explicitly unlock those.
            nextStatus = "AUTO_EVALUATION".equals(next.type()) ||
                (next.candidateFormJson() != null && !next.candidateFormJson().isBlank())
                ? "AWAITING_CANDIDATE" : "AWAITING_REVIEW";
            if (!repository.advance(communityId,applicationId,next.id(),nextStatus)) {
                throw conflict("Próxima etapa modificada concorrentemente");
            }
        }
        if (!"IN_PROGRESS".equals(applicationStatus) &&
            !repository.setApplicationStatus(communityId,applicationId,applicationStatus)) {
            throw conflict("Candidatura modificada concorrentemente");
        }

        var log = new CommunityAuditLog();
        log.setCommunity(actor.community());
        log.setActorUser(actor.user());
        log.setAction("RECRUITMENT_STAGE_DECIDED");
        log.setTargetType("RECRUITMENT_APPLICATION_STAGE");
        log.setTargetId(applicationId + ":" + stageId);
        audits.save(log);

        // Return only privileged administrative state. Candidate read endpoints
        // intentionally remain RECEIVED until separate manual publication exists.
        return new DecisionReceipt(
            repository.decision(communityId,applicationId,stageId),
            applicationStatus,nextId,nextStatus
        );
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor = authorization.requireCapability(
            auth,communityId,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        );
        if (!actor.activeMember()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Decisão exige vínculo ativo com a Community");
        }
        return actor;
    }

    private void requireIds(Long applicationId,Long stageId) {
        if (applicationId == null || applicationId <= 0 || stageId == null || stageId <= 0)
            throw invalid("Candidatura ou etapa inválida");
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa não encontrada");
    }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
