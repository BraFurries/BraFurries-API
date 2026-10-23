package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentExamReviewRepository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human review of advisory browser focus signals. Reviewers are accountable
 * managers, but browser signals are NOT evidence of intentional wrongdoing.
 */
@Service
public class RecruitmentExamReviewService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentStageDecisionRepository stages;
    private final RecruitmentCandidateAttemptRepository attempts;
    private final RecruitmentExamReviewRepository reviews;
    private final CommunityAuditLogRepository audits;

    public RecruitmentExamReviewService(RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,RecruitmentStageDecisionRepository stages,
        RecruitmentCandidateAttemptRepository attempts,
        RecruitmentExamReviewRepository reviews,CommunityAuditLogRepository audits) {
        this.gate=gate;this.authorization=authorization;this.stages=stages;
        this.attempts=attempts;this.reviews=reviews;this.audits=audits;
    }

    public record ReasonRequest(String reason) {}
    public record IncidentEvidence(Incident incident,List<Signal> signals,
        boolean truncated,int recordedFocusSignals,boolean canReinstate) {}
    public record ReviewPanel(long applicationId,long stageId,List<IncidentEvidence> incidents) {}
    public record Reinstatement(long applicationId,long stageId,long attemptId,
        Instant expiresAt,int previousFocusLosses,boolean restored) {}

    @Transactional
    public ReviewPanel panel(Authentication auth,Integer communityId,
        Long applicationId,Long stageId) {
        var actor=manager(auth,communityId);
        var context=validateStage(actor,communityId,applicationId,stageId);
        var incidents=reviews.incidents(communityId,applicationId,stageId);
        var policy=context.policy();
        var evidence=incidents.stream().map(incident->{
            var signals=reviews.signals(communityId,incident.attemptId());
            var count=reviews.focusSignals(communityId,incident.attemptId());
            boolean eligible="BLOCKED".equals(incident.status()) &&
                incident.reinstatedAt()==null && policy.maxFocusLosses()!=null &&
                count>=policy.maxFocusLosses() &&
                incident.focusLosses()>=policy.maxFocusLosses() &&
                stages.decision(communityId,applicationId,stageId)==null;
            return new IncidentEvidence(incident,
                signals.stream().limit(100).toList(),signals.size()>100,count,eligible);
        }).toList();
        audit(actor,"RECRUITMENT_EXAM_BLOCK_REVIEW_VIEWED",applicationId+":"+stageId);
        return new ReviewPanel(applicationId,stageId,evidence);
    }

    @Transactional
    public Reinstatement reinstate(Authentication auth,Integer communityId,
        Long applicationId,Long stageId,Long attemptId,ReasonRequest request) {
        var actor=manager(auth,communityId);
        if(attemptId==null || attemptId<=0) throw bad("Tentativa inválida");
        if(request==null || request.reason()==null ||
            request.reason().trim().length()<20 ||
            request.reason().trim().length()>2000)
            throw bad("Justificativa administrativa deve ter de 20 a 2000 caracteres");
        var context=validateStage(actor,communityId,applicationId,stageId);
        var incident=reviews.incident(communityId,applicationId,stageId,attemptId);
        if(incident==null) throw missing();
        if(!"BLOCKED".equals(incident.status()) || incident.reinstatedAt()!=null)
            throw conflict("Tentativa não está bloqueada ou já foi reaberta");
        if(stages.decision(communityId,applicationId,stageId)!=null)
            throw conflict("Etapa com decisão registrada não pode ser reaberta");
        var policy=context.policy();
        if(policy.maxFocusLosses()==null ||
            incident.focusLosses()<policy.maxFocusLosses() ||
            reviews.focusSignals(communityId,attemptId)<policy.maxFocusLosses())
            throw conflict("Bloqueio não foi causado pelo limite de perda de foco");
        if(attempts.attemptsLocked(communityId,applicationId,stageId).stream().anyMatch(
            a->"STARTED".equals(a.status())))
            throw conflict("Há outra tentativa ativa na etapa");
        Instant expiry=attempts.dbNow().plusSeconds(policy.timeLimitSeconds());
        try {
            reviews.insertReinstatement(communityId,applicationId,stageId,attemptId,
                actor.user().getId(),request.reason().trim(),incident.focusLosses(),expiry);
        } catch(DataIntegrityViolationException duplicate) {
            throw conflict("Esta tentativa já recebeu uma reabertura");
        }
        if(!reviews.reopenBlocked(communityId,applicationId,stageId,
            attemptId,incident.focusLosses(),expiry))
            throw conflict("A tentativa mudou durante a revisão");
        audit(actor,"RECRUITMENT_EXAM_ATTEMPT_REINSTATED",
            applicationId+":"+stageId+":"+attemptId);
        return new Reinstatement(applicationId,stageId,attemptId,expiry,
            incident.focusLosses(),true);
    }

    private record Context(RecruitmentCandidateAttemptRepository.Policy policy) {}
    /** Serialized with the same lock used by candidate submit and finalization. */
    private Context validateStage(
        CommunityAuthorizationService.CommunityAccessContext actor,
        Integer communityId,Long applicationId,Long stageId
    ) {
        if(applicationId==null || applicationId<=0 || stageId==null || stageId<=0)
            throw bad("Candidatura e etapa obrigatórias");
        var app=stages.lockApplication(communityId,applicationId);
        if(app==null) throw missing();
        if(app.candidateId()==actor.user().getId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Gestor não pode revisar o bloqueio da própria candidatura");
        if(!"IN_PROGRESS".equals(app.status()))
            throw conflict("Candidatura não está em andamento");
        var s=stages.stagesLocked(communityId,applicationId,app.versionId()).stream()
            .filter(row->row.id()==stageId).findFirst().orElse(null);
        if(s==null || !"AUTO_EVALUATION".equals(s.type()) ||
            !"AWAITING_CANDIDATE".equals(s.status()))
            throw conflict("Esta etapa não permite reabertura de prova");
        if(stages.decision(communityId,applicationId,stageId)!=null)
            throw conflict("A decisão da etapa já foi registrada");
        // Match the candidate runner's ACTIVE/PUBLISHED checks: a manager must
        // not issue an unusable reinstatement for a closed selection.
        var candidateStage=attempts.stage(communityId,applicationId,stageId);
        if(candidateStage==null || candidateStage.versionId()!=app.versionId() ||
           candidateStage.policy()==null ||
           !"IN_PROGRESS".equals(candidateStage.applicationStatus()) ||
           !"AWAITING_CANDIDATE".equals(candidateStage.stageStatus()) ||
           !"AUTO_EVALUATION".equals(candidateStage.stageType()))
            throw conflict("A prova não está disponível para a pessoa candidata");
        return new Context(candidateStage.policy());
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId) {
        gate.requireEnabledFor(communityId);
        var actor=authorization.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!actor.activeMember())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "É necessário vínculo ativo com a Community");
        return actor;
    }
    private void audit(CommunityAuthorizationService.CommunityAccessContext actor,
        String action,String target) {
        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());event.setActorUser(actor.user());
        event.setAction(action);event.setTargetType("RECRUITMENT_EXAM_REVIEW");
        event.setTargetId(target);audits.save(event);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Bloqueio indisponível");
    }
    private ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);
    }
    private ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }
}
