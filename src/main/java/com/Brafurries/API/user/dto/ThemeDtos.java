package com.Brafurries.API.user.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ThemeDtos {
    private ThemeDtos() {}

    public record ThemeResourceRequest(
        @NotBlank String resourceType,
        @NotBlank @Pattern(regexp = "[1-9]\\d*") String resourceId,
        @NotBlank @Size(max = 100) String targetName
    ) {}

    public record ThemeSaveRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotBlank String iconAction,
        @NotBlank String bannerAction,
        @NotNull List<@Valid ThemeResourceRequest> resources
    ) {}

    public record ThemeAssetResponse(
        String action,
        String url,
        String contentType,
        String sha256,
        Long sizeBytes
    ) {}

    public record ThemeResourceResponse(
        long id,
        String resourceType,
        String resourceId,
        String targetName
    ) {}

    public record ThemeResponse(
        long id,
        String guildId,
        String name,
        String description,
        ThemeAssetResponse icon,
        ThemeAssetResponse banner,
        List<ThemeResourceResponse> resources,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ThemesResponse(
        List<ThemeResponse> themes,
        ThemeApplicationResponse activeApplication
    ) {}

    public record ThemeEditableRole(
        String id,
        String name,
        Integer position,
        boolean eligible,
        String unavailableReason
    ) {}

    public record ThemeEditableChannel(
        String id,
        String name,
        String type,
        String categoryId
    ) {}

    public record ThemeEditableCategory(String id, String name) {}

    public record ThemeResourcesResponse(
        List<ThemeEditableChannel> channels,
        List<ThemeEditableCategory> categories,
        List<ThemeEditableRole> roles
    ) {}

    public record ThemePreviewChange(
        String resourceType,
        String resourceId,
        String beforeValue,
        String targetValue,
        String action
    ) {}

    public record ThemePreviewIssue(
        String code,
        String resourceType,
        String resourceId,
        String message
    ) {}

    public record ThemePreviewResponse(
        String guildId,
        long themeId,
        List<ThemePreviewChange> changes,
        List<ThemePreviewIssue> warnings,
        List<ThemePreviewIssue> blockers,
        boolean ready
    ) {}

    public record ThemeApplyRequest(@NotNull Boolean confirmed) {}

    public record ThemeRestoreRequest(
        @NotNull Boolean confirmed,
        @NotNull Boolean force
    ) {}

    public record ThemeOperationStepResponse(
        long id,
        String code,
        String status,
        String message,
        int progressCurrent,
        int progressTotal,
        JsonNode detail,
        Instant createdAt
    ) {}

    public record ThemeOperationResponse(
        long id,
        long applicationId,
        String operationType,
        String status,
        int progressCurrent,
        int progressTotal,
        String currentStep,
        JsonNode result,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        List<ThemeOperationStepResponse> steps
    ) {}

    public record ThemeApplicationResponse(
        long id,
        long themeId,
        String themeName,
        String guildId,
        String actorDiscordUserId,
        String status,
        Instant appliedAt,
        Instant restoredAt,
        String errorSummary,
        ThemeOperationResponse latestOperation
    ) {}

    public record ThemeApplicationsResponse(List<ThemeApplicationResponse> applications) {}

    public record RollbackAssetUploadRequest(
        @NotBlank String assetType,
        @NotBlank String contentType,
        @NotBlank String base64Data,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256
    ) {}

    public record ManagedThemeAssetResponse(
        String key,
        String contentType,
        String sha256,
        long sizeBytes
    ) {}
}
