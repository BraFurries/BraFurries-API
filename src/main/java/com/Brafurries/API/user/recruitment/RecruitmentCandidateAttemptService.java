package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateDtos.*;
import com.Brafurries.API.user.recruitment.RecruitmentFormDefinition.*;
import com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.StageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Candidate-attempt lifecycle with private server-side grading.
 * No candidate score disclosure, result publication or automatic stage advancement.
 */
@Service
public class RecruitmentCandidateAttemptService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authz;
    private final RecruitmentCandidateAttemptRepository repository;
    private final RecruitmentStageDecisionRepository decisions;
    private final RecruitmentObjectiveGradeRepository grades;
    private final RecruitmentObjectiveScorer scorer;
    private final RecruitmentFormValidator formValidator;
    private final RecruitmentCandidateAnswerValidator answerValidator;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper json;

    public RecruitmentCandidateAttemptService(
        RecruitmentFeatureGate gate,CommunityAuthorizationService authz,
        RecruitmentCandidateAttemptRepository repository,
        RecruitmentStageDecisionRepository decisions,
        RecruitmentObjectiveGradeRepository grades,RecruitmentObjectiveScorer scorer,
        RecruitmentFormValidator formValidator,
        RecruitmentCandidateAnswerValidator answerValidator,
        CommunityAuditLogRepository audits,ObjectMapper json
    ) {
        this.gate=gate;this.authz=authz;this.repository=repository;this.decisions=decisions;
        this.grades=grades;this.scorer=scorer;
        this.formValidator=formValidator;this.answerValidator=answerValidator;
        this.audits=audits;this.json=json;
    }

    public record ConfigureRequest(Long expectedRevision,Integer maxAttempts,
        Integer timeLimitSeconds,Instant deadlineAt,Boolean hardDeadline,
        Integer maxFocusLosses,Boolean blockCopy) {}
    public record PolicyResponse(long stageId,long versionId,long draftRevision,Policy policy) {}
    public record AttemptSession(
        long attemptId,long applicationId,long stageId,int attemptNumber,
        String status,Instant expiresAt,PublicForm form
    ) {}
    public record SubmitRequest(Map<String,JsonNode> answers) {}
    public record Submission(
        long attemptId,long applicationId,long stageId,String status,Instant submittedAt
    ) {}

    @Transactional
    public PolicyResponse configure(
        Authentication auth,Integer communityId,Long processId,Long stageId,
        ConfigureRequest request
    ) {
        var actor=manager(auth,communityId);
        if (processId==null || processId<=0 || stageId==null || stageId<=0)
            throw bad("Processo ou etapa inválidos");
        if(request==null || request.expectedRevision()==null || request.expectedRevision()<0 ||
            request.maxAttempts()==null || request.maxAttempts()<1 || request.maxAttempts()>5 ||
            request.timeLimitSeconds()==null || request.timeLimitSeconds()<60 ||
            request.timeLimitSeconds()>14400 || request.hardDeadline()==null)
            throw bad("Limites e revisão inválidos");
        // Browser-only restrictions are a later increment; reject promises we cannot enforce.
        if(request.maxFocusLosses()!=null || Boolean.TRUE.equals(request.blockCopy()))
            throw bad("Controles de foco e cópia ainda não estão disponíveis nesta versão");
        if(request.deadlineAt()!=null && request.deadlineAt().isBefore(Instant.now()))
            throw bad("O prazo final deve estar no futuro");
        var stage=repository.lockDraft(communityId,processId,stageId);
        if(stage==null) throw missing();
        if(!"DRAFT".equals(stage.processStatus()) ||
           !"DRAFT".equals(stage.versionStatus()) ||
           !"AUTO_EVALUATION".equals(stage.stageType()) ||
           stage.revision()!=request.expectedRevision())
            throw conflict("Etapa não editável ou revisão desatualizada");
        var policy=new Policy(request.maxAttempts(),request.timeLimitSeconds(),
            request.deadlineAt(),request.hardDeadline(),null,false);
        if(!repository.savePolicy(communityId,processId,stageId,stage.versionId(),
                request.expectedRevision(),policy))
            throw conflict("Rascunho alterado");
        audit(actor,"RECRUITMENT_ATTEMPT_POLICY_UPDATED",stageId);
        return new PolicyResponse(stageId,stage.versionId(),stage.revision()+1,policy);
    }

    @Transactional
    public AttemptSession start(
        Authentication auth,Integer communityId,Long applicationId,Long stageId
    ) {
        var actor=candidate(auth,communityId);
        validateIds(applicationId,stageId);
        var locked=decisions.lockApplication(communityId,applicationId);
        if(locked==null || locked.candidateId()!=actor.user().getId())
            throw missing();
        var stage=stage(communityId,applicationId,stageId,locked.versionId());
        var now=repository.dbNow();
        // Idempotent retry/resume of the exact current attempt, serialized by application.
        var attempts=repository.attempts(communityId,applicationId,stageId);
        for(var attempt:attempts) {
            if(!"STARTED".equals(attempt.status())) continue;
            if(now.isBefore(attempt.expiresAt())) return session(attempt,stage.formJson());
            repository.expire(communityId,attempt.id());
        }
        if(stage.policy().deadlineAt()!=null && !now.isBefore(stage.policy().deadlineAt()))
            throw conflict("Prazo final para iniciar tentativas encerrado");
        if(attempts.size()>=stage.policy().maxAttempts())
            throw conflict("Quantidade máxima de tentativas atingida");

        Instant expires=now.plusSeconds(stage.policy().timeLimitSeconds());
        if(stage.policy().hardDeadline() && stage.policy().deadlineAt()!=null &&
           stage.policy().deadlineAt().isBefore(expires))
            expires=stage.policy().deadlineAt();
        if(!expires.isAfter(now)) throw conflict("Prazo esgotado");
        var created=repository.create(communityId,applicationId,stage.versionId(),stageId,
            attempts.size()+1,expires);
        audit(actor,"RECRUITMENT_CANDIDATE_ATTEMPT_STARTED",created.id());
        return session(created,stage.formJson());
    }

    @Transactional
    public Submission submit(
        Authentication auth,Integer communityId,Long applicationId,Long stageId,
        Long attemptId,SubmitRequest request
    ) {
        var actor=candidate(auth,communityId);
        validateIds(applicationId,stageId);
        if(attemptId==null || attemptId<=0 || request==null || request.answers()==null)
            throw bad("Tentativa e respostas obrigatórias");
        var locked=decisions.lockApplication(communityId,applicationId);
        if(locked==null || locked.candidateId()!=actor.user().getId()) throw missing();
        var stage=stage(communityId,applicationId,stageId,locked.versionId());
        var attempt=repository.attempts(communityId,applicationId,stageId).stream()
            .filter(a->a.id()==attemptId).findFirst().orElse(null);
        if(attempt==null) throw missing();
        if(!"STARTED".equals(attempt.status()))
            throw conflict("Tentativa já encerrada");
        var now=repository.dbNow();
        if(!now.isBefore(attempt.expiresAt())) {
            repository.expire(communityId,attempt.id());
            throw conflict("O tempo da tentativa terminou");
        }
        // Hard deadline may have been reached while the candidate was answering.
        if(stage.policy().hardDeadline() && stage.policy().deadlineAt()!=null &&
           !now.isBefore(stage.policy().deadlineAt())) {
            repository.expire(communityId,attempt.id());
            throw conflict("Prazo rígido da etapa encerrado");
        }
        var form=internalForm(stage.formJson());
        var validated=answerValidator.validate(form,request.answers());
        var grading=grades.policy(communityId,stage.versionId(),stageId);
        if(grading==null)
            throw conflict("Prova sem política de correção configurada");
        var score=scorer.score(form,validated,grading.passingPercentage());
        final String serialized;
        try { serialized=json.writeValueAsString(validated); }
        catch(JsonProcessingException ex) { throw bad("Respostas inválidas"); }
        if(serialized.length()>100000) throw bad("Respostas excedem o limite");
        if(!repository.submit(communityId,attemptId,serialized))
            throw conflict("A tentativa terminou durante o envio");
        // Same DB transaction: either submission + grade + audit all commit,
        // or neither is recorded. The candidate receives no grade or answer key.
        grades.insertGrade(communityId,attemptId,score,grading.passingPercentage());
        audit(actor,"RECRUITMENT_OBJECTIVE_ATTEMPT_GRADED",attemptId);
        audit(actor,"RECRUITMENT_CANDIDATE_ATTEMPT_SUBMITTED",attemptId);
        return new Submission(attemptId,applicationId,stageId,"SUBMITTED",repository.dbNow());
    }

    private Stage stage(Integer communityId,long applicationId,long stageId,long version) {
        var stage=repository.stage(communityId,applicationId,stageId);
        if(stage==null || stage.versionId()!=version || stage.policy()==null ||
           !"IN_PROGRESS".equals(stage.applicationStatus()) ||
           !"AWAITING_CANDIDATE".equals(stage.stageStatus()) ||
           !"AUTO_EVALUATION".equals(stage.stageType()) || stage.formJson()==null)
            throw missing();
        return stage;
    }

    private AttemptSession session(Attempt attempt,String formJson) {
        var form=internalForm(formJson);
        var fields=form.questions().stream().map(q->new PublicQuestion(
            q.id(),q.label(),q.type().name(),q.required(),
            (q.options()==null?List.<Option>of():q.options()).stream()
                .map(o->new PublicOption(o.id(),o.label())).toList(),
            q.minValue(),q.maxValue())).toList();
        return new AttemptSession(attempt.id(),attempt.applicationId(),attempt.stageId(),
            attempt.number(),attempt.status(),attempt.expiresAt(),new PublicForm(fields));
    }

    private RecruitmentFormDefinition internalForm(String source) {
        try {
            var form=json.readValue(source,RecruitmentFormDefinition.class);
            return formValidator.validate(form,false,StageType.AUTO_EVALUATION);
        } catch(JsonProcessingException|IllegalArgumentException ex) {
            throw conflict("Formulário de avaliação inválido");
        }
    }

    private CommunityAuthorizationService.CommunityAccessContext candidate(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor=authz.resolve(auth,communityId);
        if(!actor.activeMember()) throw missing();
        return actor;
    }
    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor=authz.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!actor.activeMember()) throw missing();
        return actor;
    }
    private void audit(CommunityAuthorizationService.CommunityAccessContext actor,
                       String action,long target) {
        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());
        event.setActorUser(actor.user());
        event.setAction(action);
        event.setTargetType("RECRUITMENT_CANDIDATE_ATTEMPT");
        event.setTargetId(Long.toString(target));
        audits.save(event);
    }
    private void validateIds(Long applicationId,Long stageId) {
        if(applicationId==null||applicationId<=0||stageId==null||stageId<=0)
            throw bad("Candidatura ou etapa inválidas");
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Tentativa indisponível");
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
