package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentHumanReviewRepository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human evaluation is NOT a stage decision or vote. No application status is
 * changed here. Both reviewer capability and live stage designation are required.
 */
@Service
public class RecruitmentHumanReviewService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authz;
    private final RecruitmentHumanReviewRepository repository;
    private final RecruitmentCandidateAnswerValidator answerValidator;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper mapper;

    public RecruitmentHumanReviewService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authz,
        RecruitmentHumanReviewRepository repository,
        RecruitmentCandidateAnswerValidator answerValidator,
        CommunityAuditLogRepository audits,
        ObjectMapper mapper
    ) {
        this.gate = gate;
        this.authz = authz;
        this.repository = repository;
        this.answerValidator = answerValidator;
        this.audits = audits;
        this.mapper = mapper;
    }

    public record AssignmentRequest(Integer userId, Integer roleId) {}
    public record AssessmentRequest(
        BigDecimal score, String opinion, Map<String, JsonNode> staffAnswers
    ) {}
    public record AssignmentReceipt(long id, long stageId, Integer userId, Integer roleId) {}

    @Transactional(readOnly = true)
    public List<Assignment> assignments(Authentication auth, Integer communityId, Long stageId) {
        requireManager(auth, communityId);
        var stage = requireStage(communityId, stageId);
        return repository.assignments(communityId, stage.stageId());
    }

    @Transactional
    public AssignmentReceipt assign(
        Authentication auth, Integer communityId, Long stageId, AssignmentRequest request
    ) {
        var actor = requireManager(auth, communityId);
        StageIdentity stage = requireStage(communityId, stageId);
        if (request == null || (request.userId() == null) == (request.roleId() == null)) {
            throw invalid("Escolha exatamente uma pessoa ou cargo interno");
        }
        if (request.userId() != null && (request.userId() <= 0 ||
            !repository.eligiblePerson(communityId, request.userId()))) {
            throw invalid("Pessoa não pertence à Community");
        }
        if (request.roleId() != null && (request.roleId() <= 0 ||
            !repository.eligibleRole(communityId, request.roleId()))) {
            throw invalid("Cargo interno inexistente ou inativo");
        }
        final long id;
        try {
            id = repository.assign(
                communityId, stage, request.userId(), request.roleId(), actor.user().getId()
            );
        } catch (DataIntegrityViolationException collision) {
            throw conflict("A designação já existe ou não está mais disponível");
        }
        audit(actor, "RECRUITMENT_REVIEWER_ASSIGNED", "RECRUITMENT_STAGE", stageId.toString());
        return new AssignmentReceipt(id, stage.stageId(), request.userId(), request.roleId());
    }

    @Transactional
    public void revoke(
        Authentication auth, Integer communityId, Long stageId, Long assignmentId
    ) {
        var actor = requireManager(auth, communityId);
        StageIdentity stage = requireStage(communityId, stageId);
        if (assignmentId == null || assignmentId <= 0 ||
            !repository.remove(communityId, stage.stageId(), assignmentId)) {
            throw missing();
        }
        audit(actor, "RECRUITMENT_REVIEWER_REVOKED",
            "RECRUITMENT_STAGE_REVIEWER", assignmentId.toString());
    }

    /**
     * Only the designated reviewer sees the Staff questions; strip any unexpected
     * answer keys and grading metadata from a malformed historical JSON document.
     */
    @Transactional(readOnly = true)
    public RecruitmentFormDefinition assessmentForm(
        Authentication auth, Integer communityId, Long applicationId, Long stageId
    ) {
        var actor = requireReviewer(auth, communityId);
        if (applicationId == null || applicationId <= 0 || stageId == null || stageId <= 0) {
            throw invalid("Candidatura ou etapa inválida");
        }
        var target = repository.assessmentTarget(communityId, applicationId, stageId, false);
        if (target == null) throw missing();
        if (target.candidateUserId() == actor.user().getId()) {
            throw forbidden("Não é possível avaliar a própria candidatura");
        }
        if (!repository.assignedToStage(
            communityId, target.stageId(), target.processVersionId(), actor.user().getId()
        )) throw missing();
        if (!List.of("AWAITING_REVIEW", "IN_PROGRESS").contains(target.stageStatus())
            || "AUTO_EVALUATION".equals(target.stageType())) {
            throw conflict("Esta etapa não está disponível para avaliação");
        }
        if (target.staffFormJson() == null) {
            return new RecruitmentFormDefinition(List.of());
        }
        try {
            var form = mapper.readValue(target.staffFormJson(), RecruitmentFormDefinition.class);
            if (form == null || form.questions() == null) throw new IllegalStateException("Formulário interno inválido");
            var safe = form.questions().stream().map(q -> new RecruitmentFormDefinition.Question(
                q.id(), q.label(), q.type(), q.required(), q.options(), q.minValue(),
                q.maxValue(), null, List.of()
            )).toList();
            return new RecruitmentFormDefinition(safe);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Formulário de Staff persistido inválido", invalid);
        }
    }

    @Transactional
    public Assessment assess(
        Authentication auth, Integer communityId, Long applicationId, Long stageId,
        AssessmentRequest request
    ) {
        var actor = requireReviewer(auth, communityId);
        if (applicationId == null || applicationId <= 0 || stageId == null || stageId <= 0) {
            throw invalid("Candidatura ou etapa inválida");
        }
        var target = repository.lockAssessmentTarget(communityId, applicationId, stageId);
        if (target == null) throw missing();
        int reviewerId = actor.user().getId();
        if (target.candidateUserId() == reviewerId) {
            throw forbidden("Não é possível avaliar a própria candidatura");
        }
        if (!repository.assignedToStage(
            communityId, target.stageId(), target.processVersionId(), reviewerId
        )) throw missing();
        if (!List.of("AWAITING_REVIEW", "IN_PROGRESS").contains(target.stageStatus())) {
            throw conflict("A etapa ainda não está disponível para avaliação humana");
        }
        if ("AUTO_EVALUATION".equals(target.stageType())) {
            throw conflict("Esta etapa exige avaliação automática");
        }
        if (request == null) throw invalid("Avaliação ausente");
        BigDecimal score = request.score();
        if (score != null && (
            score.compareTo(BigDecimal.ZERO) < 0
            || score.compareTo(new BigDecimal("100")) > 0
            || score.scale() > 2
        )) throw invalid("Nota precisa estar entre 0 e 100, com até duas casas");
        String opinion = request.opinion() == null ? null : request.opinion().strip();
        if (opinion != null && opinion.isEmpty()) opinion = null;
        if (opinion != null && opinion.length() > 4000) throw invalid("Parecer muito longo");
        Map<String, JsonNode> answers = request.staffAnswers() == null
            ? Map.of() : request.staffAnswers();
        if (answers.size() > 60) throw invalid("Formulário de avaliação muito grande");
        if (target.staffFormJson() == null) {
            if (!answers.isEmpty()) throw invalid("Etapa sem formulário interno");
        } else {
            final RecruitmentFormDefinition definition;
            try {
                definition = mapper.readValue(target.staffFormJson(), RecruitmentFormDefinition.class);
            } catch (JsonProcessingException broken) {
                throw new IllegalStateException("Formulário de Staff persistido inválido", broken);
            }
            if (definition == null || definition.questions() == null) {
                throw new IllegalStateException("Formulário de Staff inválido");
            }
            if (definition.questions().isEmpty()) {
                if (!answers.isEmpty()) throw invalid("Formulário interno vazio");
            } else {
                answerValidator.validate(definition, answers);
            }
        }
        if (score == null && opinion == null && answers.isEmpty()) {
            throw invalid("Informe nota, parecer ou avaliação");
        }
        final String answersJson;
        try {
            answersJson = target.staffFormJson() == null ? null : mapper.writeValueAsString(answers);
        } catch (JsonProcessingException error) {
            throw invalid("Respostas de avaliação inválidas");
        }
        try {
            repository.submit(
                communityId, applicationId, stageId, reviewerId, score, opinion, answersJson
            );
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("Esta etapa já foi avaliada por você");
        }
        audit(actor, "RECRUITMENT_ASSESSMENT_SUBMITTED",
            "RECRUITMENT_APPLICATION_STAGE", applicationId + ":" + stageId);
        return repository.ownAssessment(communityId, applicationId, stageId, reviewerId);
    }

    @Transactional(readOnly = true)
    public Assessment ownAssessment(
        Authentication auth, Integer communityId, Long applicationId, Long stageId
    ) {
        var actor = requireReviewer(auth, communityId);
        if (applicationId == null || applicationId <= 0 || stageId == null || stageId <= 0) {
            throw invalid("Candidatura ou etapa inválida");
        }
        StageIdentity stage = requireStage(communityId, stageId);
        if (!repository.assignedToStage(
            communityId, stage.stageId(), stage.processVersionId(), actor.user().getId()
        )) throw missing();
        Assessment response = repository.ownAssessment(
            communityId, applicationId, stageId, actor.user().getId()
        );
        if (response == null) throw missing();
        return response;
    }

    private StageIdentity requireStage(Integer communityId, Long stageId) {
        if (stageId == null || stageId <= 0) throw invalid("Etapa inválida");
        StageIdentity stage = repository.stage(communityId, stageId);
        if (stage == null) throw missing();
        return stage;
    }

    private CommunityAuthorizationService.CommunityAccessContext requireManager(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        return authz.requireCapability(auth, communityId, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
    }

    private CommunityAuthorizationService.CommunityAccessContext requireReviewer(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var actor = authz.requireCapability(auth, communityId, CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS);
        if (!actor.activeMember()) throw missing();
        return actor;
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action, String targetType, String targetId
    ) {
        var log = new CommunityAuditLog();
        log.setCommunity(actor.community());
        log.setActorUser(actor.user());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        audits.save(log);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa ou avaliação indisponível");
    }
}
