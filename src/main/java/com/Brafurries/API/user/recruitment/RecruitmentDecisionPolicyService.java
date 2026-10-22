package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecruitmentDecisionPolicyService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authorization;
    private final RecruitmentDecisionPolicyRepository policies;
    private final CommunityAuditLogRepository audits;
    private final ObjectMapper json;

    public RecruitmentDecisionPolicyService(
        RecruitmentFeatureGate gate,CommunityAuthorizationService authorization,
        RecruitmentDecisionPolicyRepository policies,
        CommunityAuditLogRepository audits,ObjectMapper json
    ) {
        this.gate=gate; this.authorization=authorization; this.policies=policies;
        this.audits=audits; this.json=json;
    }

    public record SavePolicyRequest(RecruitmentDecisionPolicy policy,Long expectedRevision) {}
    public record StagePolicyResult(
        long stageId,long processId,String stageType,String versionStatus,
        long draftRevision,RecruitmentDecisionPolicy policy
    ) {}

    @Transactional(readOnly=true)
    public StagePolicyResult detail(Authentication auth,Integer communityId,Long processId,Long stageId) {
        manager(auth,communityId);
        validateIds(processId,stageId);
        var stored=policies.find(communityId,processId,stageId);
        if (stored == null) throw missing();
        return result(stored);
    }

    @Transactional
    public StagePolicyResult update(
        Authentication auth,Integer communityId,Long processId,Long stageId,
        SavePolicyRequest request
    ) {
        var actor=manager(auth,communityId);
        validateIds(processId,stageId);
        if (request == null || request.expectedRevision() == null ||
            request.expectedRevision() < 0) throw bad("Revisão obrigatória");
        var validated=RecruitmentDecisionPolicy.validate(request.policy());
        final String serialized;
        try {
            serialized=json.writeValueAsString(validated);
        } catch (JsonProcessingException error) {
            throw bad("Política de decisão inválida");
        }
        final RecruitmentDecisionPolicyRepository.StoredPolicy stored;
        try {
            stored=policies.update(communityId,processId,stageId,
                request.expectedRevision(),serialized);
        } catch (OptimisticLockingFailureException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Rascunho desatualizado ou já publicado",conflict);
        } catch (IllegalArgumentException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,conflict.getMessage(),conflict);
        }
        if (stored == null) throw missing();
        var event=new CommunityAuditLog();
        event.setCommunity(actor.community());
        event.setActorUser(actor.user());
        event.setAction("RECRUITMENT_DECISION_POLICY_UPDATED");
        event.setTargetType("RECRUITMENT_STAGE");
        event.setTargetId(String.valueOf(stageId));
        audits.save(event);
        return result(stored);
    }

    private StagePolicyResult result(RecruitmentDecisionPolicyRepository.StoredPolicy stored) {
        return new StagePolicyResult(stored.stageId(),stored.processId(),stored.stageType(),
            stored.versionStatus(),stored.draftRevision(),
            RecruitmentDecisionPolicy.parse(json,stored.policyJson()));
    }

    private CommunityAuthorizationService.CommunityAccessContext manager(
        Authentication auth,Integer communityId
    ) {
        gate.requireEnabledFor(communityId);
        return authorization.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
    }

    private void validateIds(Long processId,Long stageId) {
        if (processId==null || processId<=0 || stageId==null || stageId<=0)
            throw bad("Processo ou etapa inválidos");
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Etapa indisponível");
    }
}
