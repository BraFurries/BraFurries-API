package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit manager-triggered finalization; the server grade determines the
 * outcome. The candidate never sees it before separate manual publication.
 */
@Service
public class RecruitmentObjectiveStageFinalizationService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentStageDecisionRepository decisions;
    private final RecruitmentCandidateAttemptRepository attempts;
    private final RecruitmentObjectiveGradeRepository grades;
    private final CommunityAuditLogRepository audits;

    public RecruitmentObjectiveStageFinalizationService(
        RecruitmentFeatureGate gate,CommunityAuthorizationService authorization,
        RecruitmentStageDecisionRepository decisions,
        RecruitmentCandidateAttemptRepository attempts,
        RecruitmentObjectiveGradeRepository grades,
        CommunityAuditLogRepository audits
    ) {
        this.gate=gate;this.authorization=authorization;
        this.decisions=decisions;this.attempts=attempts;this.grades=grades;
        this.audits=audits;
    }

    public record Finalization(
        Decision decision,long applicationId,long stageId,
        Long selectedAttemptId,boolean passed,BigDecimal selectedPercentage,
        String applicationStatus,Long nextStageId,String nextStageStatus
    ) {}

    @Transactional
    public Finalization finalizeStage(Authentication auth,Integer communityId,
        Long applicationId,Long stageId) {
        var actor=manager(auth,communityId);
        if(applicationId==null || applicationId<=0 || stageId==null || stageId<=0)
            throw bad("Candidatura e etapa obrigatórias");

        // Same serialization lock as start/submit and human/vote decisions.
        var application=decisions.lockApplication(communityId,applicationId);
        if(application==null) throw missing();
        if(application.candidateId()==actor.user().getId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Não pode decidir sua própria candidatura");
        if(!"IN_PROGRESS".equals(application.status()))
            throw conflict("Candidatura não está mais em avaliação");
        var stages=decisions.stages(communityId,applicationId,application.versionId());
        int currentIndex=-1;
        for(int i=0;i<stages.size();i++)
            if(stages.get(i).id()==stageId) { currentIndex=i;break; }
        if(currentIndex<0) throw missing();
        var current=stages.get(currentIndex);
        if(!"AUTO_EVALUATION".equals(current.type()) ||
            !"AWAITING_CANDIDATE".equals(current.status()))
            throw conflict("Etapa não aceita consolidação objetiva");
        for(int i=0;i<stages.size();i++) {
            if(i<currentIndex && !"COMPLETED".equals(stages.get(i).status()))
                throw conflict("Existem etapas anteriores não concluídas");
            if(i>currentIndex && !"PENDING".equals(stages.get(i).status()))
                throw conflict("Existe etapa posterior já iniciada");
        }
        if(decisions.decision(communityId,applicationId,stageId)!=null)
            throw conflict("Etapa já foi consolidada");

        var attemptPolicy=attempts.policy(communityId,application.versionId(),stageId);
        var scoringPolicy=grades.policy(communityId,application.versionId(),stageId);
        if(attemptPolicy==null || scoringPolicy==null)
            throw conflict("Política congelada incompleta");
        var now=attempts.dbNow();
        if(now==null) throw new IllegalStateException("Relógio do banco indisponível");
        var history=attempts.attempts(communityId,applicationId,stageId);
        int submitted=0;
        for(var attempt:history) {
            if("SUBMITTED".equals(attempt.status())) submitted++;
            else if("STARTED".equals(attempt.status())) {
                // Force a current transition before closing; never steal a live attempt.
                if(now.isBefore(attempt.expiresAt()))
                    throw conflict("Há tentativa em andamento dentro do prazo");
                if(!attempts.expire(communityId,attempt.id()))
                    throw conflict("Tentativa alterada concorrentemente");
            } else if(!"EXPIRED".equals(attempt.status()) && !"BLOCKED".equals(attempt.status()))
                throw conflict("Tentativa em estado desconhecido");
        }
        boolean deadlineReached=attemptPolicy.deadlineAt()!=null
            && !now.isBefore(attemptPolicy.deadlineAt());
        boolean attemptsExhausted=history.size()>=attemptPolicy.maxAttempts();
        if(!deadlineReached && !attemptsExhausted)
            throw conflict("Ainda é possível iniciar novas tentativas");

        List<Grade> scores=grades.grades(communityId,applicationId,stageId);
        // Submitted-but-ungraded rows are a data consistency blocker, never a zero.
        if(scores.size()!=submitted)
            throw conflict("Há tentativas enviadas sem correção concluída");
        var selected=RecruitmentObjectiveGradeSelector.select(
            scores,scoringPolicy.resultStrategy());
        String outcome=selected!=null && selected.passed() ? "APPROVE":"REJECT";
        Long selectedId=selected==null?null:selected.attemptId();
        BigDecimal selectedPercentage=selected==null?null:
            BigDecimal.valueOf(selected.earnedPoints()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(selected.possiblePoints()),4,
                    java.math.RoundingMode.HALF_UP);
        String reason=selected==null
            ? "Prova objetiva sem tentativa enviada válida após encerramento das oportunidades"
            : "Nota objetiva da tentativa "+selectedId+": "+selected.earnedPoints()
                +"/"+selected.possiblePoints()+" pontos; política "
                +scoringPolicy.resultStrategy()+"; corte "+scoringPolicy.passingPercentage()+"%";
        try {
            decisions.insertObjectiveDecision(communityId,applicationId,stageId,
                actor.user().getId(),outcome,reason,selectedId);
        } catch(DataIntegrityViolationException duplicate) {
            throw conflict("Decisão objetiva já registrada ou referência inválida");
        }
        if(!decisions.completeObjective(communityId,applicationId,stageId))
            throw conflict("Etapa modificada concorrentemente");

        String applicationStatus="IN_PROGRESS";
        Long nextStageId=null;
        String nextStageStatus=null;
        if("REJECT".equals(outcome) || currentIndex==stages.size()-1) {
            if("REJECT".equals(outcome))
                decisions.blockRemaining(communityId,applicationId);
            applicationStatus="AWAITING_PUBLICATION";
        } else {
            var next=stages.get(currentIndex+1);
            nextStageId=next.id();
            nextStageStatus="AUTO_EVALUATION".equals(next.type()) ||
                (next.candidateFormJson()!=null && !next.candidateFormJson().isBlank())
                ? "AWAITING_CANDIDATE":"AWAITING_REVIEW";
            if(!decisions.advance(communityId,applicationId,next.id(),nextStageStatus))
                throw conflict("Próxima etapa modificada concorrentemente");
        }
        if(!"IN_PROGRESS".equals(applicationStatus) &&
            !decisions.setApplicationStatus(communityId,applicationId,applicationStatus))
            throw conflict("Candidatura modificada concorrentemente");

        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());event.setActorUser(actor.user());
        event.setAction("RECRUITMENT_OBJECTIVE_STAGE_FINALIZED");
        event.setTargetType("RECRUITMENT_APPLICATION_STAGE");
        event.setTargetId(applicationId+":"+stageId);audits.save(event);

        return new Finalization(
            decisions.decision(communityId,applicationId,stageId),
            applicationId,stageId,selectedId,"APPROVE".equals(outcome),
            selectedPercentage,applicationStatus,nextStageId,nextStageStatus
        );
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor=authorization.requireCapability(
            auth,communityId,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!actor.activeMember())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Consolidação exige vínculo ativo com a Community");
        return actor;
    }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Etapa indisponível");
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
