package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentTrackingDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V1 read-only triage. No decisions, notes, scores or result publication.
 * Do not infer candidate-visible status from internal application/stage state.
 */
@Service
public class RecruitmentTrackingService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentTrackingRepository repository;
    private final ObjectMapper json;
    private final CommunityAuditLogRepository audits;

    public RecruitmentTrackingService(
        RecruitmentFeatureGate gate,
        CommunityAuthorizationService authorization,
        RecruitmentTrackingRepository repository,
        ObjectMapper json,
        CommunityAuditLogRepository audits
    ) {
        this.gate = gate;
        this.authorization = authorization;
        this.repository = repository;
        this.json = json;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public List<OwnApplication> own(
        Authentication auth, Integer communityId, Long beforeId
    ) {
        var actor = candidate(auth, communityId);
        return repository.own(communityId, actor.user().getId(), cursor(beforeId));
    }

    @Transactional(readOnly = true)
    public OwnApplication ownDetail(
        Authentication auth, Integer communityId, Long applicationId
    ) {
        var actor = candidate(auth, communityId);
        var application = repository.ownById(
            communityId, actor.user().getId(), applicationId
        );
        if (application == null) throw missing();
        return application;
    }

    @Transactional(readOnly = true)
    public List<ReviewProcess> reviewProcesses(Authentication auth, Integer communityId) {
        var reviewer = reviewer(auth, communityId);
        return repository.reviewProcesses(communityId, reviewer.user().getId());
    }

    @Transactional(readOnly = true)
    public List<ReviewQueueItem> queue(
        Authentication auth, Integer communityId, Long processId, Long beforeId
    ) {
        var reviewer = reviewer(auth, communityId);
        if (processId == null || processId <= 0) throw invalid("Processo inválido");
        return repository.queue(
            communityId, processId, reviewer.user().getId(), cursor(beforeId)
        );
    }

    @Transactional
    public ReviewApplication reviewDetail(
        Authentication auth, Integer communityId, Long applicationId
    ) {
        var reviewer = reviewer(auth, communityId);
        if (applicationId == null || applicationId <= 0) throw invalid("Candidatura inválida");
        var item = repository.reviewById(communityId, applicationId);
        if (item == null) throw missing();
        // Do not allow staff to review their own submissions through privileged
        // endpoints even when they have a direct or role-based grant.
        if (item.candidateUserId().equals(reviewer.user().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Não é possível revisar a própria candidatura");
        }
        // A global reviewer grant alone must not reveal candidate answers.
        if (!repository.canReview(communityId, applicationId, reviewer.user().getId())) throw missing();
        var raw = repository.candidateAnswers(communityId, applicationId, reviewer.user().getId());
        JsonNode answers = null;
        if (raw != null) {
            try {
                answers = json.readTree(raw.json());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Respostas persistidas inválidas", exception);
            }
            if (answers == null || !answers.isObject()) {
                throw new IllegalStateException("Respostas persistidas não são um objeto");
            }
        }
        var result = new ReviewApplication(
            item, repository.reviewStages(communityId, applicationId, reviewer.user().getId()),
            raw == null ? null : raw.stageId(), answers
        );
        var log = new CommunityAuditLog();
        log.setCommunity(reviewer.community());
        log.setActorUser(reviewer.user());
        log.setAction("RECRUITMENT_APPLICATION_VIEWED");
        log.setTargetType("RECRUITMENT_APPLICATION");
        log.setTargetId(applicationId.toString());
        audits.save(log);
        return result;
    }

    private CommunityAuthorizationService.CommunityAccessContext candidate(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var context = authorization.resolve(auth, communityId);
        if (!context.activeMember()) throw missing();
        return context;
    }

    private CommunityAuthorizationService.CommunityAccessContext reviewer(
        Authentication auth, Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        var reviewer = authorization.requireCapability(
            auth, communityId, CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS
        );
        // Owners and Community admins can possess global capabilities without
        // an active membership. Private applicant data still requires membership.
        if (!reviewer.activeMember()) throw missing();
        return reviewer;
    }

    private Long cursor(Long value) {
        if (value != null && value <= 0) throw invalid("Cursor inválido");
        return value;
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidatura indisponível");
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
