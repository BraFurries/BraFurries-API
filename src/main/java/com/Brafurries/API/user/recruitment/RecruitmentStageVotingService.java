package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentStageVoteRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separate immutable ballot from private technical assessment and final decision.
 * Only an explicit privileged consolidation can transition the application.
 */
@Service
public class RecruitmentStageVotingService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authz;
    private final RecruitmentHumanReviewRepository assessments;
    private final RecruitmentStageVoteRepository votes;
    private final RecruitmentStageDecisionRepository decisions;
    private final RecruitmentStageDecisionService finalizer;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper json;

    public RecruitmentStageVotingService(
        RecruitmentFeatureGate gate,CommunityAuthorizationService authz,
        RecruitmentHumanReviewRepository assessments,RecruitmentStageVoteRepository votes,
        RecruitmentStageDecisionRepository decisions,RecruitmentStageDecisionService finalizer,
        CommunityAuditLogRepository audits,ObjectMapper json
    ) {
        this.gate=gate;this.authz=authz;this.assessments=assessments;this.votes=votes;
        this.decisions=decisions;this.finalizer=finalizer;this.audits=audits;this.json=json;
    }

    public record CastRequest(String choice) {}
    public record TieOverride(String outcome,String reason) {}
    public record VoteSummary(
        long applicationId,long stageId,int approves,int rejects,int quorum,
        boolean quorumReached,boolean tie,RecruitmentDecisionPolicy.TiePolicy tiePolicy
    ) {}
    public record VotingDecision(DecisionReceipt receipt,VoteSummary tally) {}

    @Transactional(readOnly=true)
    public Ballot mine(
        Authentication auth,Integer communityId,Long applicationId,Long stageId
    ) {
        var actor=reviewer(auth,communityId);
        var target=requireTarget(communityId,applicationId,stageId,actor.user().getId());
        if (!assessments.assignedToStage(
            communityId,stageId,target.processVersionId(),actor.user().getId()))
            throw missing();
        var ballot=votes.own(communityId,applicationId,stageId,actor.user().getId());
        if (ballot==null) throw missing();
        return ballot;
    }

    @Transactional
    public Ballot cast(
        Authentication auth,Integer communityId,Long applicationId,Long stageId,
        CastRequest request
    ) {
        var actor=reviewer(auth,communityId);
        requireIds(applicationId,stageId);
        if (request==null || !List.of("APPROVE","REJECT").contains(
            request.choice()==null?"":request.choice()))
            throw bad("Voto deve ser APPROVE ou REJECT");
        // The application is the same serialization point used by administrative decisions.
        var application=decisions.lockApplication(communityId,applicationId);
        if (application==null) throw missing();
        var target=requireTarget(communityId,applicationId,stageId,actor.user().getId());
        if (application.candidateId()!=target.candidateUserId() ||
            application.versionId()!=target.processVersionId() ||
            !"IN_PROGRESS".equals(application.status()) ||
            !"AWAITING_REVIEW".equals(target.stageStatus()) ||
            "AUTO_EVALUATION".equals(target.stageType())) throw conflict("Etapa não aceita votos");
        var policy=policy(communityId,stageId,application.versionId());
        if (policy.decisionMode()!=RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE)
            throw conflict("Esta etapa não usa votação");
        if (!assessments.assignedToStage(
            communityId,stageId,target.processVersionId(),actor.user().getId()))
            throw missing();
        if (assessments.ownAssessment(
            communityId,applicationId,stageId,actor.user().getId())==null)
            throw conflict("Registre seu parecer antes de votar");
        if (decisions.decision(communityId,applicationId,stageId)!=null)
            throw conflict("A etapa já foi decidida");
        try {
            votes.cast(communityId,applicationId,stageId,actor.user().getId(),request.choice());
        } catch (DataIntegrityViolationException duplicateOrInvalid) {
            throw conflict("Voto já registrado ou parecer indisponível");
        }
        audit(actor,"RECRUITMENT_BALLOT_CAST",applicationId+":"+stageId);
        return votes.own(communityId,applicationId,stageId,actor.user().getId());
    }

    @Transactional
    public VoteSummary tally(
        Authentication auth,Integer communityId,Long applicationId,Long stageId
    ) {
        var actor=manager(auth,communityId);
        requireIds(applicationId,stageId);
        var target=requireTarget(communityId,applicationId,stageId,actor.user().getId());
        var policy=policy(communityId,stageId,target.processVersionId());
        if (policy.decisionMode()!=RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE)
            throw conflict("Etapa não usa votação");
        var count=votes.tally(communityId,applicationId,stageId);
        audit(actor,"RECRUITMENT_VOTE_TALLY_VIEWED",applicationId+":"+stageId);
        return summary(applicationId,stageId,count,policy);
    }

    @Transactional
    public VotingDecision finalizeVote(
        Authentication auth,Integer communityId,Long applicationId,Long stageId,
        TieOverride override
    ) {
        var actor=manager(auth,communityId);
        requireIds(applicationId,stageId);
        var app=decisions.lockApplication(communityId,applicationId);
        if (app==null) throw missing();
        var target=requireTarget(communityId,applicationId,stageId,actor.user().getId());
        if (app.candidateId()!=target.candidateUserId() ||
            app.versionId()!=target.processVersionId() ||
            !"IN_PROGRESS".equals(app.status()) ||
            !"AWAITING_REVIEW".equals(target.stageStatus()))
            throw conflict("Etapa não está aguardando decisão");
        var policy=policy(communityId,stageId,app.versionId());
        if (policy.decisionMode()!=RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE)
            throw conflict("Etapa não usa votação");
        if (decisions.decision(communityId,applicationId,stageId)!=null)
            throw conflict("Esta etapa já possui decisão");
        if (decisions.assessmentCount(communityId,applicationId,stageId)
            < policy.minimumAssessments())
            throw conflict("Pareceres mínimos ainda não registrados");
        var tally=votes.lockedTally(communityId,applicationId,stageId);
        var summary=summary(applicationId,stageId,tally,policy);
        if (!summary.quorumReached())
            throw conflict("Quórum de votos ainda não atingido");

        String source;
        String result;
        String reason;
        if (!tally.tied()) {
            if (override!=null && override.outcome()!=null)
                throw bad("Resultado majoritário não aceita override");
            source="VOTE_MAJORITY";
            result=tally.majority();
            reason="Resultado coletivo por maioria: "+tally.approves()+
                " aprovações e "+tally.rejects()+" reprovações";
        } else if (policy.tiePolicy()==RecruitmentDecisionPolicy.TiePolicy.REJECT_ON_TIE) {
            if (override!=null && override.outcome()!=null)
                throw bad("Esta política resolve o empate por reprovação");
            source="VOTE_TIE_REJECT";
            result="REJECT";
            reason="Reprovação por empate conforme política: "+
                tally.approves()+" a "+tally.rejects();
        } else {
            if (override==null || !List.of("APPROVE","REJECT").contains(
                override.outcome()==null?"":override.outcome()) ||
                override.reason()==null || override.reason().strip().length()<10 ||
                override.reason().strip().length()>1800)
                throw conflict("Empate exige decisão explícita e justificativa do gestor");
            source="VOTE_TIE_OVERRIDE";
            result=override.outcome();
            reason="Desempate manual após votos empatados: "+override.reason().strip();
        }

        // Reuse the existing locked, transactional stage progression and decision uniqueness.
        var receipt=finalizer.decideFromVotes(auth,communityId,applicationId,stageId,
            new DecisionRequest(result,reason),source);
        audit(actor,"RECRUITMENT_BALLOTS_CONSOLIDATED",applicationId+":"+stageId);
        return new VotingDecision(receipt,summary);
    }

    private VoteSummary summary(
        long applicationId,long stageId,Tally tally,RecruitmentDecisionPolicy policy
    ) {
        return new VoteSummary(applicationId,stageId,tally.approves(),tally.rejects(),
            policy.minimumVotes(),tally.count()>=policy.minimumVotes(),
            tally.tied(),policy.tiePolicy());
    }

    private RecruitmentDecisionPolicy policy(Integer communityId,long stageId,long version) {
        return RecruitmentDecisionPolicy.parse(json,
            decisions.policyJson(communityId,stageId,version));
    }

    private RecruitmentHumanReviewRepository.AssessmentTarget requireTarget(
        Integer communityId,Long applicationId,Long stageId,int actorId
    ) {
        requireIds(applicationId,stageId);
        var target=assessments.assessmentTarget(communityId,applicationId,stageId,false);
        if(target==null) throw missing();
        if(target.candidateUserId()==actorId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Não é permitido votar ou decidir a própria candidatura");
        return target;
    }

    private CommunityAuthorizationService.CommunityAccessContext reviewer(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var a=authz.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS);
        if(!a.activeMember()) throw missing();
        return a;
    }
    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var a=authz.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!a.activeMember()) throw missing();
        return a;
    }
    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,String action,String target
    ) {
        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());
        event.setActorUser(actor.user());
        event.setAction(action);
        event.setTargetType("RECRUITMENT_APPLICATION_STAGE");
        event.setTargetId(target);
        audits.save(event);
    }
    private void requireIds(Long applicationId,Long stageId) {
        if(applicationId==null || stageId==null || applicationId<=0 || stageId<=0)
            throw bad("Candidatura ou etapa inválida");
    }
    private ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);
    }
    private ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Votação indisponível");
    }
}
