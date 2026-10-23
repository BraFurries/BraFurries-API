package com.Brafurries.API.user.recruitment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stage-local, frozen policy. Legacy three-field JSON remains MANUAL/INDIVIDUAL compatible. */
public record RecruitmentDecisionPolicy(
    ReviewMode reviewMode, DecisionMode decisionMode, Integer minimumAssessments,
    Integer minimumVotes, TiePolicy tiePolicy
) {
    public enum ReviewMode { INDIVIDUAL, COLLECTIVE }
    public enum DecisionMode { MANUAL, AUTOMATIC_VOTE }
    public enum TiePolicy { REJECT_ON_TIE, MANAGER_OVERRIDE }

    public RecruitmentDecisionPolicy(
        ReviewMode reviewMode,DecisionMode decisionMode,Integer minimumAssessments
    ) {
        this(reviewMode,decisionMode,minimumAssessments,null,null);
    }

    public static RecruitmentDecisionPolicy legacyDefault() {
        return new RecruitmentDecisionPolicy(ReviewMode.INDIVIDUAL,DecisionMode.MANUAL,1);
    }

    public static RecruitmentDecisionPolicy parse(ObjectMapper mapper,String stored) {
        if (stored==null || stored.isBlank()) return legacyDefault();
        try {
            var parsed=mapper.readValue(stored,RecruitmentDecisionPolicy.class);
            // Previously saved draft AUTO_VOTE policies had only three fields.
            // Keep them readable for staff configuration, but NEVER executable
            // until quorum + tie rule are explicitly saved.
            if (parsed!=null && parsed.decisionMode()==DecisionMode.AUTOMATIC_VOTE &&
                parsed.reviewMode()==ReviewMode.COLLECTIVE &&
                parsed.minimumAssessments()!=null &&
                parsed.minimumAssessments()>=2 && parsed.minimumAssessments()<=20 &&
                (parsed.minimumVotes()==null || parsed.tiePolicy()==null)) {
                return parsed;
            }
            return validate(parsed);
        } catch (JsonProcessingException | ResponseStatusException malformed) {
            throw new IllegalStateException("Política de decisão persistida inválida",malformed);
        }
    }

    public static RecruitmentDecisionPolicy validate(RecruitmentDecisionPolicy policy) {
        if (policy==null || policy.reviewMode()==null || policy.decisionMode()==null ||
            policy.minimumAssessments()==null || policy.minimumAssessments()<1 ||
            policy.minimumAssessments()>20) {
            throw bad("Informe modo de avaliação, decisão e de 1 a 20 pareceres");
        }
        if (policy.reviewMode()==ReviewMode.INDIVIDUAL && policy.minimumAssessments()!=1)
            throw bad("Avaliação individual exige exatamente um parecer");
        if (policy.reviewMode()==ReviewMode.COLLECTIVE && policy.minimumAssessments()<2)
            throw bad("Avaliação coletiva exige ao menos dois pareceres");
        if (policy.decisionMode()==DecisionMode.AUTOMATIC_VOTE) {
            if (policy.reviewMode()!=ReviewMode.COLLECTIVE)
                throw bad("Votação exige revisão coletiva");
            if (policy.minimumVotes()==null || policy.minimumVotes()<2 ||
                policy.minimumVotes()>20 || policy.tiePolicy()==null)
                throw bad("Votação exige quórum de 2 a 20 votos e regra explícita para empate");
        } else if (policy.minimumVotes()!=null || policy.tiePolicy()!=null) {
            throw bad("Votos e desempate só podem ser configurados em votação automática");
        }
        return policy;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
