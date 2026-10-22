package com.Brafurries.API.user.recruitment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frozen, stage-local policy. Stored in the existing evaluation_config_json column.
 * AUTOMATIC_VOTE is intentionally NOT executable until a vote/quorum engine exists.
 */
public record RecruitmentDecisionPolicy(
    ReviewMode reviewMode, DecisionMode decisionMode, Integer minimumAssessments
) {
    public enum ReviewMode { INDIVIDUAL, COLLECTIVE }
    public enum DecisionMode { MANUAL, AUTOMATIC_VOTE }

    /** Backward compatible with existing stages created before policy configuration. */
    public static RecruitmentDecisionPolicy legacyDefault() {
        return new RecruitmentDecisionPolicy(ReviewMode.INDIVIDUAL,DecisionMode.MANUAL,1);
    }

    public static RecruitmentDecisionPolicy parse(ObjectMapper mapper, String stored) {
        if (stored == null || stored.isBlank()) return legacyDefault();
        try {
            RecruitmentDecisionPolicy parsed = mapper.readValue(stored,RecruitmentDecisionPolicy.class);
            return validate(parsed);
        } catch (JsonProcessingException | ResponseStatusException malformed) {
            throw new IllegalStateException("Política de decisão persistida inválida",malformed);
        }
    }

    public static RecruitmentDecisionPolicy validate(RecruitmentDecisionPolicy policy) {
        if (policy == null || policy.reviewMode() == null || policy.decisionMode() == null ||
            policy.minimumAssessments() == null || policy.minimumAssessments() < 1 ||
            policy.minimumAssessments() > 20) {
            throw bad("Informe modo de avaliação, decisão e de 1 a 20 pareceres");
        }
        if (policy.reviewMode() == ReviewMode.INDIVIDUAL && policy.minimumAssessments() != 1) {
            throw bad("Avaliação individual exige exatamente um parecer");
        }
        if (policy.reviewMode() == ReviewMode.COLLECTIVE && policy.minimumAssessments() < 2) {
            throw bad("Avaliação coletiva exige ao menos dois pareceres");
        }
        if (policy.decisionMode() == DecisionMode.AUTOMATIC_VOTE &&
            policy.reviewMode() != ReviewMode.COLLECTIVE) {
            throw bad("Votação automática exige avaliação coletiva");
        }
        return policy;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
