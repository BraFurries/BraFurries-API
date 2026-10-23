package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.*;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Private administrative policy and provisional scoring read-model, never candidate-facing. */
@Service
public class RecruitmentObjectiveGradeService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authz;
    private final RecruitmentCandidateAttemptRepository attempts;
    private final RecruitmentObjectiveGradeRepository grades;
    private final CommunityAuditLogRepository audits;
    public RecruitmentObjectiveGradeService(RecruitmentFeatureGate gate,
        CommunityAuthorizationService authz,
        RecruitmentCandidateAttemptRepository attempts,
        RecruitmentObjectiveGradeRepository grades,
        CommunityAuditLogRepository audits) {
        this.gate=gate;this.authz=authz;this.attempts=attempts;
        this.grades=grades;this.audits=audits;
    }

    public record ConfigureRequest(Long expectedRevision,BigDecimal passingPercentage,
                                    String resultStrategy) {}
    public record Configured(long processVersionId,long stageId,long revision,
                             GradePolicy policy) {}
    public record GradeSummary(long applicationId,long stageId,GradePolicy policy,
        List<Grade> attempts,Grade selected,boolean provisional) {}

    @Transactional
    public Configured configure(Authentication auth,Integer communityId,
        Long processId,Long stageId,ConfigureRequest request) {
        var actor=manager(auth,communityId);
        if(processId==null||processId<=0||stageId==null||stageId<=0 ||
           request==null || request.expectedRevision()==null ||
           request.expectedRevision()<0 ||
           request.passingPercentage()==null ||
           request.passingPercentage().compareTo(BigDecimal.ONE)<0 ||
           request.passingPercentage().compareTo(new BigDecimal("100"))>0 ||
           request.passingPercentage().scale()>2 ||
           !List.of("BEST","LAST").contains(
               request.resultStrategy()==null?"":request.resultStrategy())) {
            throw bad("Nota de corte (1–100%) e estratégia BEST/LAST obrigatórias");
        }
        var locked=attempts.lockDraft(communityId,processId,stageId);
        if(locked==null) throw missing();
        if(!"DRAFT".equals(locked.processStatus()) ||
           !"DRAFT".equals(locked.versionStatus()) ||
           !"AUTO_EVALUATION".equals(locked.stageType()) ||
           locked.revision()!=request.expectedRevision())
            throw conflict("Etapa não editável ou revisão desatualizada");
        var policy=new GradePolicy(request.passingPercentage(),request.resultStrategy());
        if(!grades.configure(communityId,processId,locked.versionId(),
            stageId,request.expectedRevision(),policy))
            throw conflict("Rascunho alterado");
        audit(actor,"RECRUITMENT_GRADE_POLICY_UPDATED",stageId);
        return new Configured(locked.versionId(),stageId,locked.revision()+1,policy);
    }

    /** No results become final or candidate-visible during this delivery. */
    @Transactional
    public GradeSummary managerSummary(Authentication auth,Integer communityId,
        Long applicationId,Long stageId) {
        var actor=manager(auth,communityId);
        if(applicationId==null||applicationId<=0||stageId==null||stageId<=0)
            throw bad("Candidatura e etapa obrigatórias");
        var stage=grades.stage(communityId,applicationId,stageId);
        if(stage==null || !"AUTO_EVALUATION".equals(stage.type())) throw missing();
        if(stage.candidateId()==actor.user().getId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Não é permitido consultar notas da própria candidatura");
        var policy=grades.policy(communityId,stage.versionId(),stageId);
        if(policy==null) throw missing();
        var rows=grades.grades(communityId,applicationId,stageId);
        Grade selected=null;
        if(!rows.isEmpty()) {
            if("LAST".equals(policy.resultStrategy())) {
                selected=rows.get(rows.size()-1);
            } else if("BEST".equals(policy.resultStrategy())) {
                selected=rows.stream()
                    .max(Comparator.comparing((Grade g)->BigDecimal.valueOf(g.earnedPoints())
                        .divide(BigDecimal.valueOf(g.possiblePoints()),12,java.math.RoundingMode.HALF_UP))
                        .thenComparingLong(g->-g.attemptId()))
                    .orElseThrow();
            } else throw new IllegalStateException("Política de seleção inválida");
        }
        audit(actor,"RECRUITMENT_GRADE_SUMMARY_VIEWED",applicationId);
        return new GradeSummary(applicationId,stageId,policy,List.copyOf(rows),selected,true);
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId) {
        gate.requireEnabledFor(communityId);
        var actor=authz.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!actor.activeMember()) throw missing();
        return actor;
    }
    private void audit(CommunityAuthorizationService.CommunityAccessContext actor,
        String action,long target) {
        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());event.setActorUser(actor.user());
        event.setAction(action);event.setTargetType("RECRUITMENT_OBJECTIVE_GRADE");
        event.setTargetId(Long.toString(target));audits.save(event);
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Correção indisponível");
    }
}
