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
    private final RecruitmentExamNoticeRepository notices;
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
        CommunityAuditLogRepository audits,ObjectMapper json,
        RecruitmentExamNoticeRepository notices
    ) {
        this.gate=gate;this.authz=authz;this.repository=repository;this.decisions=decisions;
        this.notices=notices;this.grades=grades;this.scorer=scorer;
        this.formValidator=formValidator;this.answerValidator=answerValidator;
        this.audits=audits;this.json=json;
    }

    public record ConfigureRequest(Long expectedRevision,Integer maxAttempts,
        Integer timeLimitSeconds,Instant deadlineAt,Boolean hardDeadline,
        Integer maxFocusLosses,Boolean blockCopy) {}
    public record PolicyResponse(long stageId,long versionId,long draftRevision,Policy policy) {}
    public record AttemptSession(
        long attemptId,long applicationId,long stageId,int attemptNumber,
        String status,Instant expiresAt,Instant serverNow,PublicForm form,
        Integer maxFocusLosses,boolean blockCopy,int focusLosses
    ) {}
    public record SubmitRequest(Map<String,JsonNode> answers) {}
    public record DraftRequest(Long expectedRevision,Map<String,JsonNode> answers) {}
    public record DraftResponse(long attemptId,long revision,Instant savedAt,
        Map<String,JsonNode> answers) {}

    public record IntegrityEventRequest(String eventId,String eventType,String source) {}
    public record IntegrityEventReceipt(
        String eventId,String status,int focusLosses,Integer maxFocusLosses,boolean blocked
    ) {}
    /** Only numeric exam grade: NEVER pass/fail, gabarito or final selection outcome. */
    public record PublicScore(long attemptId,int earnedPoints,int possiblePoints,
        java.math.BigDecimal percentage) {}
    public record Submission(
        long attemptId,long applicationId,long stageId,String status,Instant submittedAt,
        PublicScore score
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
        if(request.maxFocusLosses()!=null &&
            (request.maxFocusLosses()<1 || request.maxFocusLosses()>10))
            throw bad("Limite de saídas deve ser de 1 a 10");
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
            request.deadlineAt(),request.hardDeadline(),
            request.maxFocusLosses(),Boolean.TRUE.equals(request.blockCopy()));
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
        if(attempts.stream().anyMatch(a->"BLOCKED".equals(a.status())))
            throw conflict("Prova bloqueada por limite de saídas da tela");
        for(var attempt:attempts) {
            if(!"STARTED".equals(attempt.status())) continue;
            if(now.isBefore(attempt.expiresAt())) return session(communityId,attempt,stage.formJson(),now,stage.policy());
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
        return session(communityId,created,stage.formJson(),now,stage.policy());
    }

    /** Own-attempt draft only; never exposes the answer key or a score. */
    @Transactional
    public DraftResponse draft(Authentication auth,Integer communityId,
        Long applicationId,Long stageId,Long attemptId) {
        var ctx=editableDraft(auth,communityId,applicationId,stageId,attemptId);
        return loadDraft(communityId,applicationId,stageId,ctx.attempt().id());
    }

    /** Revision-based CAS prevents stale tabs and slow requests from overwriting newer data. */
    @Transactional
    public DraftResponse saveDraft(Authentication auth,Integer communityId,
        Long applicationId,Long stageId,Long attemptId,DraftRequest request) {
        if(request==null || request.expectedRevision()==null ||
           request.expectedRevision()<0 || request.answers()==null)
            throw bad("Revisão e respostas do rascunho são obrigatórias");
        var ctx=editableDraft(auth,communityId,applicationId,stageId,attemptId);
        var validated=answerValidator.validatePartial(internalForm(ctx.stage().formJson()),
            request.answers());
        final String serialized;
        try { serialized=json.writeValueAsString(validated); }
        catch(JsonProcessingException ex) { throw bad("Rascunho inválido"); }
        if(serialized.length()>100000) throw bad("Rascunho excede o limite");
        if(!repository.saveDraft(communityId,applicationId,stageId,attemptId,
            request.expectedRevision(),serialized))
            throw conflict("Rascunho alterado em outra sessão ou tentativa encerrada");
        return loadDraft(communityId,applicationId,stageId,attemptId);
    }

    private record DraftContext(Stage stage,Attempt attempt) {}
    private DraftContext editableDraft(Authentication auth,Integer communityId,
        Long applicationId,Long stageId,Long attemptId) {
        var actor=candidate(auth,communityId);
        validateIds(applicationId,stageId);
        if(attemptId==null || attemptId<=0) throw bad("Tentativa inválida");
        var locked=decisions.lockApplication(communityId,applicationId);
        if(locked==null || locked.candidateId()!=actor.user().getId()) throw missing();
        var st=stage(communityId,applicationId,stageId,locked.versionId());
        var at=repository.attemptsLocked(communityId,applicationId,stageId)
            .stream().filter(a->a.id()==attemptId).findFirst().orElse(null);
        if(at==null) throw missing();
        var now=repository.dbNow();
        if(!"STARTED".equals(at.status()) || !now.isBefore(at.expiresAt()))
            throw conflict("Tentativa bloqueada, encerrada ou expirada");
        if(st.policy().hardDeadline() && st.policy().deadlineAt()!=null
           && !now.isBefore(st.policy().deadlineAt())
           && !repository.hasReinstatement(communityId,applicationId,stageId,attemptId))
            throw conflict("Prazo rígido da etapa encerrado");
        return new DraftContext(st,at);
    }

    private DraftResponse loadDraft(Integer communityId,long applicationId,
        long stageId,long attemptId) {
        var saved=repository.draft(communityId,applicationId,stageId,attemptId);
        if(saved==null) throw missing();
        final Map<String,JsonNode> answers;
        try {
            answers=saved.answersJson()==null ? Map.of() :
                json.readValue(saved.answersJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String,JsonNode>>(){});
        } catch(JsonProcessingException ex) {
            throw conflict("Rascunho armazenado inválido");
        }
        return new DraftResponse(attemptId,saved.revision(),saved.savedAt(),answers);
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
           !now.isBefore(stage.policy().deadlineAt()) &&
           !repository.hasReinstatement(communityId,applicationId,stageId,attemptId)) {
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
        PublicScore feedback="AFTER_SUBMISSION".equals(grading.scoreVisibility())
            ? publicScore(attemptId,score.earnedPoints(),score.possiblePoints()):null;
        return new Submission(attemptId,applicationId,stageId,"SUBMITTED",repository.dbNow(),feedback);
    }

    @Transactional(readOnly=true)
    public List<AvailableExam> availableExams(Authentication auth,Integer communityId,
        Long applicationId) {
        var actor=candidate(auth,communityId);
        if(applicationId==null||applicationId<=0) throw bad("Candidatura inválida");
        Integer owner=decisions.candidateId(communityId,applicationId);
        if(owner==null || owner!=actor.user().getId()) throw missing();
        return repository.availableExams(communityId,applicationId,actor.user().getId());
    }

    @Transactional
    public List<PublicScore> ownScores(Authentication auth,Integer communityId,
        Long applicationId,Long stageId) {
        var actor=candidate(auth,communityId);
        validateIds(applicationId,stageId);
        Integer owner=decisions.candidateId(communityId,applicationId);
        if(owner==null || owner!=actor.user().getId()) throw missing();
        var stage=grades.stage(communityId,applicationId,stageId);
        if(stage==null || !"AUTO_EVALUATION".equals(stage.type())) throw missing();
        var grading=grades.policy(communityId,stage.versionId(),stageId);
        if(grading==null || !"AFTER_SUBMISSION".equals(grading.scoreVisibility()))
            throw missing();
        var scores=grades.grades(communityId,applicationId,stageId).stream()
            .map(g->publicScore(g.attemptId(),g.earnedPoints(),g.possiblePoints()))
            .toList();
        audit(actor,"RECRUITMENT_CANDIDATE_GRADE_VIEWED",stageId);
        return scores;
    }

    @Transactional
    public PolicyResponse policy(Authentication auth,Integer communityId,
        Long processId,Long stageId) {
        manager(auth,communityId);
        if(processId==null || processId<=0 || stageId==null || stageId<=0)
            throw bad("Etapa inválida");
        var stage=repository.lockDraft(communityId,processId,stageId);
        if(stage==null || !"AUTO_EVALUATION".equals(stage.stageType())) throw missing();
        var policy=repository.policy(communityId,stage.versionId(),stageId);
        if(policy==null) throw missing();
        return new PolicyResponse(stageId,stage.versionId(),stage.revision(),policy);
    }

    private PublicScore publicScore(long id,int earned,int total) {
        return new PublicScore(id,earned,total,
            java.math.BigDecimal.valueOf(earned).multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(total),2,java.math.RoundingMode.HALF_UP));
    }

    /**
     * Browser-provided integrity events are advisory, not proof of cheating.
     * The application's FOR UPDATE lock serializes start, submit, event and finalization.
     */
    @Transactional
    public IntegrityEventReceipt recordIntegrityEvent(
        Authentication auth,Integer communityId,Long applicationId,Long stageId,
        Long attemptId,IntegrityEventRequest request
    ) {
        var actor=candidate(auth,communityId);
        validateIds(applicationId,stageId);
        if(attemptId==null || attemptId<=0 || request==null ||
            request.eventId()==null || request.eventType()==null || request.source()==null)
            throw bad("Identificador, tipo e origem do evento são obrigatórios");
        final String uuid;
        try { uuid=java.util.UUID.fromString(request.eventId()).toString(); }
        catch(IllegalArgumentException ex) { throw bad("Identificador de evento inválido"); }
        if(!uuid.equals(request.eventId()))
            throw bad("Identificador de evento deve ser UUID canônico");
        boolean focus="FOCUS_LOST".equals(request.eventType());
        boolean copied="COPY_PREVENTED".equals(request.eventType());
        if(!(focus && List.of("TAB_HIDDEN","WINDOW_BLUR").contains(request.source())) &&
           !(copied && List.of("COPY","CUT","CONTEXT_MENU").contains(request.source())))
            throw bad("Tipo ou origem de evento inválidos");

        var locked=decisions.lockApplication(communityId,applicationId);
        if(locked==null || locked.candidateId()!=actor.user().getId()) throw missing();
        var attempt=repository.attemptsLocked(communityId,applicationId,stageId)
            .stream().filter(a->a.id()==attemptId).findFirst().orElse(null);
        if(attempt==null) throw missing();

        // A terminal block closes the stage immediately. A legitimate retry of
        // the SAME recorded UUID must still be idempotent after that transition.
        // Verify tenant, candidate and attempt ownership before checking the UUID.
        if(repository.eventExists(communityId,attemptId,uuid)) {
            var frozen=repository.policy(communityId,locked.versionId(),stageId);
            if(frozen==null) throw missing();
            int current=repository.focusLosses(communityId,attemptId);
            return new IntegrityEventReceipt(uuid,attempt.status(),current,
                frozen.maxFocusLosses(),"BLOCKED".equals(attempt.status()));
        }
        var stage=stage(communityId,applicationId,stageId,locked.versionId());
        var policy=stage.policy();
        if((focus && policy.maxFocusLosses()==null) || (copied && !policy.blockCopy()))
            throw conflict("Este monitoramento não está habilitado na etapa");
        if(!"STARTED".equals(attempt.status()) ||
           !repository.dbNow().isBefore(attempt.expiresAt()))
            throw conflict("Tentativa encerrada ou expirada");
        // Copying remains blocked in browser regardless of logging cap; bound
        // voluntary event storage to prevent excessively large audit tables.
        if(copied && repository.eventCount(communityId,attemptId)>=100)
            throw conflict("Limite de eventos informativos atingido");
        repository.insertIntegrityEvent(communityId,attemptId,uuid,
            request.eventType(),request.source());
        int count=repository.focusLosses(communityId,attemptId);
        boolean blocked=false;
        if(focus) {
            if(!repository.incrementFocusLoss(communityId,attemptId))
                throw conflict("Contagem de foco alterada durante a tentativa");
            count++;
            if(count>=policy.maxFocusLosses()) {
                if(!repository.blockAttempt(communityId,attemptId,policy.maxFocusLosses()))
                    throw conflict("Tentativa alterada durante o bloqueio");
                blocked=true;
                notices.record(communityId,applicationId,stageId,attemptId,
                    "BLOCKED",null);
                // Terminal focus block: close the stage automatically under the
                // application lock, without impersonating a manager or publishing
                // any selection outcome. An approved appeal can undo this transition.
                if(!decisions.blockObjective(communityId,applicationId,stageId) ||
                   !decisions.setApplicationStatus(communityId,applicationId,
                       "AWAITING_PUBLICATION"))
                    throw conflict("A etapa foi alterada durante o bloqueio");
            }
        }
        audit(actor,blocked?"RECRUITMENT_EXAM_ATTEMPT_BLOCKED":
            focus?"RECRUITMENT_EXAM_FOCUS_LOST":"RECRUITMENT_EXAM_COPY_PREVENTED",
            attemptId);
        return new IntegrityEventReceipt(uuid,blocked?"BLOCKED":"STARTED",
            count,policy.maxFocusLosses(),blocked);
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

    private AttemptSession session(Integer communityId,Attempt attempt,String formJson,
        Instant serverNow,Policy policy) {
        var form=internalForm(formJson);
        var fields=form.questions().stream().map(q->new PublicQuestion(
            q.id(),q.label(),q.type().name(),q.required(),
            (q.options()==null?List.<Option>of():q.options()).stream()
                .map(o->new PublicOption(o.id(),o.label())).toList(),
            q.minValue(),q.maxValue())).toList();
        return new AttemptSession(attempt.id(),attempt.applicationId(),attempt.stageId(),
            attempt.number(),attempt.status(),attempt.expiresAt(),serverNow,new PublicForm(fields),
            policy.maxFocusLosses(),policy.blockCopy(),
            repository.focusLosses(communityId,attempt.id()));
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
