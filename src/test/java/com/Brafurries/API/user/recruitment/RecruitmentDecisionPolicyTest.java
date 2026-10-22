package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecruitmentDecisionPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void legacyPolicyRemainsIndividualManual() {
        var policy = RecruitmentDecisionPolicy.parse(json,null);
        assertEquals(RecruitmentDecisionPolicy.ReviewMode.INDIVIDUAL,policy.reviewMode());
        assertEquals(RecruitmentDecisionPolicy.DecisionMode.MANUAL,policy.decisionMode());
        assertEquals(1,policy.minimumAssessments());
    }
    @Test void collectivePolicyMaySpecifyMinimumOfTwoToTwenty() {
        var policy = RecruitmentDecisionPolicy.validate(new RecruitmentDecisionPolicy(
            RecruitmentDecisionPolicy.ReviewMode.COLLECTIVE,
            RecruitmentDecisionPolicy.DecisionMode.MANUAL,3));
        assertEquals(3,policy.minimumAssessments());
    }
    @Test void invalidModesOrThresholdsAreRejected() {
        assertThrows(ResponseStatusException.class,() ->
            RecruitmentDecisionPolicy.validate(new RecruitmentDecisionPolicy(
                RecruitmentDecisionPolicy.ReviewMode.INDIVIDUAL,
                RecruitmentDecisionPolicy.DecisionMode.MANUAL,2)));
        assertThrows(ResponseStatusException.class,() ->
            RecruitmentDecisionPolicy.validate(new RecruitmentDecisionPolicy(
                RecruitmentDecisionPolicy.ReviewMode.COLLECTIVE,
                RecruitmentDecisionPolicy.DecisionMode.MANUAL,1)));
        assertThrows(ResponseStatusException.class,() ->
            RecruitmentDecisionPolicy.validate(new RecruitmentDecisionPolicy(
                RecruitmentDecisionPolicy.ReviewMode.INDIVIDUAL,
                RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE,1)));
        assertThrows(ResponseStatusException.class,() ->
            RecruitmentDecisionPolicy.validate(new RecruitmentDecisionPolicy(
                RecruitmentDecisionPolicy.ReviewMode.COLLECTIVE,
                RecruitmentDecisionPolicy.DecisionMode.AUTOMATIC_VOTE,21)));
    }
    @Test void corruptFrozenPolicyFailsClosed() {
        assertThrows(IllegalStateException.class,() ->
            RecruitmentDecisionPolicy.parse(json,"{\"reviewMode\":\"INVENTED\"}"));
    }
}
