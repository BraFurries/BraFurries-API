package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionDeskRepository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consolidated, manager-only read model. It never makes decisions,
 * publishes results, or relaxes reviewer-specific grants.
 */
@Service
public class RecruitmentDecisionDeskService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentDecisionDeskRepository repository;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper json;

    public RecruitmentDecisionDeskService(
        RecruitmentFeatureGate gate,CommunityAuthorizationService authorization,
        RecruitmentDecisionDeskRepository repository,
        CommunityAuditLogRepository audits,ObjectMapper json
    ) {
        this.gate=gate; this.authorization=authorization; this.repository=repository;
        this.audits=audits; this.json=json;
    }

    public record StageSummary(
        long stageId,int position,String publicName,String stageType,String internalStatus,
        int assessmentCount,RecruitmentDecisionPolicy policy,
        String decisionOutcome,String decisionReason,Integer decidedByUserId,Instant decidedAt
    ) {}
    public record ApplicationDetail(
        QueueItem application,List<StageSummary> stages,JsonNode candidateAnswers
    ) {}
    public record AssessmentView(
        long id,int evaluatorUserId,BigDecimal score,String opinion,
        JsonNode staffAnswers,Instant submittedAt
    ) {}
    public record StageEvidence(
        StageSummary stage,List<AssessmentView> assessments,boolean truncated
    ) {}

    @Transactional
    public List<QueueItem> queue(
        Authentication auth,Integer communityId,Long processId,Long beforeId
    ) {
        var actor=manager(auth,communityId);
        if (processId==null || processId<=0) throw bad("Processo inválido");
        if (beforeId!=null && beforeId<=0) throw bad("Cursor inválido");
        var items=repository.queue(communityId,processId,actor.user().getId(),beforeId);
        audit(actor,"RECRUITMENT_MANAGER_QUEUE_VIEWED","RECRUITMENT",processId.toString());
        return items;
    }

    @Transactional
    public ApplicationDetail detail(
        Authentication auth,Integer communityId,Long processId,Long applicationId
    ) {
        var actor=manager(auth,communityId);
        var application=requireApplication(communityId,processId,applicationId,
            actor.user().getId());
        var stages=repository.stages(communityId,applicationId,application.processVersionId())
            .stream().map(this::summary).toList();
        JsonNode answers=parseObject(
            repository.applicationAnswers(communityId,applicationId,application.processVersionId()));
        audit(actor,"RECRUITMENT_MANAGER_APPLICATION_VIEWED","RECRUITMENT_APPLICATION",
            applicationId.toString());
        return new ApplicationDetail(application,stages,answers);
    }

    @Transactional
    public StageEvidence evidence(
        Authentication auth,Integer communityId,Long processId,
        Long applicationId,Long stageId
    ) {
        var actor=manager(auth,communityId);
        var application=requireApplication(communityId,processId,applicationId,
            actor.user().getId());
        if (stageId==null || stageId<=0) throw bad("Etapa inválida");
        var stage=repository.stages(communityId,applicationId,application.processVersionId())
            .stream().filter(s->s.stageId()==stageId).findFirst().orElse(null);
        if (stage==null) throw missing();
        if (!List.of("IN_PROGRESS","AWAITING_REVIEW","COMPLETED",
            "AWAITING_PUBLICATION").contains(stage.internalStatus())) {
            throw missing();
        }
        var rows=repository.evidence(
            communityId,applicationId,application.processVersionId(),stageId);
        var visible=rows.stream()
            .map(row->new AssessmentView(
                row.id(),row.evaluatorUserId(),row.score(),row.opinion(),
                parseObject(row.staffAnswersJson()),row.submittedAt()))
            .toList();
        audit(actor,"RECRUITMENT_MANAGER_EVIDENCE_VIEWED","RECRUITMENT_APPLICATION_STAGE",
            applicationId+":"+stageId);
        return new StageEvidence(summary(stage),visible,stage.assessmentCount()>visible.size());
    }

    private StageSummary summary(StageRecord stage) {
        // No evaluation forms, confidential gabaritos or private candidate answers in stage metadata.
        return new StageSummary(
            stage.stageId(),stage.position(),stage.publicName(),stage.stageType(),
            stage.internalStatus(),stage.assessmentCount(),
            "AUTO_EVALUATION".equals(stage.stageType())
                ? RecruitmentDecisionPolicy.legacyDefault()
                : RecruitmentDecisionPolicy.parse(json,stage.policyJson()),
            stage.decisionOutcome(),stage.decisionReason(),stage.decidedByUserId(),
            stage.decidedAt()
        );
    }

    private QueueItem requireApplication(
        Integer communityId,Long processId,Long applicationId,Integer actorId
    ) {
        if (processId==null || processId<=0 || applicationId==null || applicationId<=0)
            throw bad("Processo ou candidatura inválidos");
        var application=repository.application(communityId,processId,applicationId);
        if (application==null) throw missing();
        if (application.candidateUserId()==actorId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Não é possível consultar a própria candidatura como gestor");
        }
        return application;
    }

    private JsonNode parseObject(String stored) {
        if (stored==null) return null;
        try {
            var parsed=json.readTree(stored);
            if (parsed==null || !parsed.isObject()) throw new IllegalStateException(
                "Respostas persistidas fora do formato de objeto");
            return parsed;
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("Respostas persistidas inválidas",malformed);
        }
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor=authorization.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if (!actor.activeMember()) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,"Gestor precisa ter vínculo ativo na Community");
        return actor;
    }

    private void audit(CommunityAuthorizationService.CommunityAccessContext actor,
                       String action,String targetType,String targetId) {
        var record=new CommunityAuditLog();
        record.setCommunity(actor.community());
        record.setActorUser(actor.user());
        record.setAction(action);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        audits.save(record);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Candidatura ou etapa indisponível");
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
