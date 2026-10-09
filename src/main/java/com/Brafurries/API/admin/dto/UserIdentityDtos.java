package com.Brafurries.API.admin.dto;

import com.Brafurries.API.entity.user.UserIdentityLinkSource;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class UserIdentityDtos {

    private UserIdentityDtos() {
    }

    public record CreateIdentityLinkRequest(
        @NotNull Integer otherUserId,
        @NotNull UserIdentityLinkStatus status,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record UpdateIdentityLinkRequest(
        @NotNull UserIdentityLinkStatus status,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record RevokeIdentityLinkRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record DiscordAccount(String discordUserId, String username, String displayName) {
    }

    public record UserSummary(
        Integer userId,
        String username,
        String displayName,
        String email,
        List<DiscordAccount> discordAccounts
    ) {
    }

    public record IdentityLinkView(
        Long id,
        UserSummary userA,
        UserSummary userB,
        UserIdentityLinkStatus status,
        UserIdentityLinkSource source,
        String reason,
        Integer createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        Integer revokedByUserId,
        String revocationReason
    ) {
    }

    public record UserIdentityResponse(
        Integer requestedUserId,
        List<UserSummary> confirmedAccounts,
        List<IdentityLinkView> confirmedLinks,
        List<IdentityLinkView> suspectedLinks
    ) {
    }

    public record WarningHistory(
        Integer id,
        Integer sourceUserId,
        List<String> sourceDiscordIds,
        Integer communityId,
        String communityName,
        LocalDate date,
        String reason,
        boolean expired,
        Integer appliedByUserId,
        String appliedByName
    ) {
    }

    public record BanHistory(
        Integer id,
        Integer sourceUserId,
        List<String> sourceDiscordIds,
        Integer communityId,
        String communityName,
        LocalDate date,
        String reason,
        Boolean canAppeal,
        LocalDate validUntil,
        Integer appliedByUserId,
        String appliedByName,
        LocalDateTime registeredAt,
        LocalDateTime revokedAt,
        Integer revokedByUserId,
        String revocationReason
    ) {
    }

    public record NoteHistory(
        Integer id,
        Integer sourceUserId,
        List<String> sourceDiscordIds,
        String note,
        Integer authorUserId,
        String authorName
    ) {
    }

    public record ModerationHistoryResponse(
        Integer requestedUserId,
        Integer communityId,
        List<Integer> confirmedUserIds,
        List<WarningHistory> warnings,
        List<BanHistory> bans,
        List<NoteHistory> notes,
        List<IdentityLinkView> suspectedLinks
    ) {
    }
}
