package com.Brafurries.API.user.recruitment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public final class RecruitmentPublicationDtos {
    private RecruitmentPublicationDtos() {}

    /**
     * V1 eligibility policy. Membership ACTIVE is non-negotiable and enforced
     * separately with CommunityMembershipStatusResolver at application time.
     */
    public record EligibilityRules(
        @Min(0) @Max(3650) int minMembershipDays,
        boolean requireVip,
        boolean requirePartner
    ) {}

    public record UpdateEligibilityRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotNull @Valid EligibilityRules eligibility
    ) {}

    public record TermsText(String text) {}

    public record UpdateTermsRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotNull String termsText
    ) {}

    public record PublishRequest(@NotNull @PositiveOrZero Long expectedRevision) {}

    public record PublicationCheck(
        Long processId,
        Long processVersionId,
        Long draftRevision,
        boolean ready,
        int stageCount,
        List<String> blockers,
        EligibilityRules configuredEligibility
    ) {}

    /** Admin-only: does not imply intake is open or candidates can see the process. */
    public record PublicationResult(
        Long processId,
        Long versionId,
        Long draftRevision,
        String processStatus,
        String versionStatus,
        String enrollmentStatus,
        String visibility
    ) {}
}
