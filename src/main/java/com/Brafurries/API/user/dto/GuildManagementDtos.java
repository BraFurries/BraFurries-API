package com.Brafurries.API.user.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.time.LocalDateTime;

public final class GuildManagementDtos {
    private GuildManagementDtos() {}

    public record GuildRole(String id, String name, Integer position, boolean managed,
                            @JsonAlias("editable_by_bot") boolean editableByBot) {}
    public record GuildChannel(String id, String name, String type,
                               @JsonAlias("category_id") String categoryId) {}
    public record GuildCategory(String id, String name) {}
    public record BotTopRole(String id, String name, Integer position) {}
    public record RoleHierarchyEntry(String id, String name, Integer position, boolean managed) {}
    public record BotCapabilities(@JsonAlias("can_manage_channels") boolean canManageChannels,
                                  @JsonAlias("can_manage_roles") boolean canManageRoles,
                                  @JsonAlias("can_apply") boolean canApply,
                                  @JsonAlias("missing_permissions") List<String> missingPermissions,
                                  @JsonAlias("has_administrator") Boolean hasAdministrator,
                                  @JsonAlias("role_hierarchy_ok") Boolean roleHierarchyOk,
                                  @JsonAlias("protection_ready") Boolean protectionReady,
                                  @JsonAlias("bot_top_role") BotTopRole botTopRole,
                                  @JsonAlias("roles_above_bot") List<RoleHierarchyEntry> rolesAboveBot) {
        public BotCapabilities(boolean canManageChannels, boolean canManageRoles, boolean canApply,
                               List<String> missingPermissions) {
            this(canManageChannels, canManageRoles, canApply, missingPermissions,
                null, null, null, null, List.of());
        }
    }
    public record GuildResources(@JsonAlias("guild_id") String guildId, List<GuildRole> roles,
                                 List<GuildChannel> channels, List<GuildCategory> categories,
                                 @JsonAlias("bot_capabilities") BotCapabilities botCapabilities) {}
    public record AutoJoinRolesConfig(@JsonAlias("enabled") boolean enabled, @JsonAlias("roleIds") List<Long> roleIds) {}
    public record AutoJoinRoleResponse(String id, String name, boolean missing, Integer position,
                                       boolean protectedRole, String protectedReason) {}
    public record AutoJoinWarning(String code, String message) {}
    public record AutoJoinRolesResponse(boolean enabled, List<AutoJoinRoleResponse> roles,
                                        String portariaVisitorRoleId, List<AutoJoinWarning> warnings) {}
    public record AutoJoinEnabledRequest(@NotNull Boolean enabled) {}

    /** Snowflakes remain strings so JavaScript clients never lose precision. */
    public record ServerChannelsConfig(String birthdayChannelId, List<String> aiChannelIds,
                                       boolean aiChannelLimitEnabled, String bumpProcessingChannelId) {}
    public record BirthdayChannelRequest(String channelId) {}
    public record AiChannelsRequest(@NotNull Boolean channelLimitEnabled, @NotNull List<String> channelIds) {}
    public record BumpProcessingChannelRequest(String channelId) {}

    public record BumpConfigResponse(BumpProcessingConfig processing, BumpWarningConfig warning,
                                     BumpIndividualRewardConfig individualReward,
                                     BumpMonthlyRankingConfig monthlyRanking,
                                     BumpOperationalState operational) {}
    public record BumpProcessingConfig(String channelId) {}
    /** sourceChannelId is a deprecated alias of processing.channelId kept for client compatibility. */
    public record BumpWarningConfig(boolean enabled, String sourceChannelId, String targetChannelId,
                                    List<String> messages) {}
    public record BumpIndividualRewardConfig(BumpRoleRewardConfig role, BumpCoinRewardConfig coins) {}
    public record BumpRoleRewardConfig(boolean enabled, String roleId, int durationMinutes, String message) {}
    public record BumpCoinRewardConfig(boolean enabled, Integer amount) {}
    public record BumpMonthlyRankingConfig(boolean enabled, String sourceChannelId, String roleId,
                                           List<BumpMonthlyRewardTier> rewards) {}
    public record BumpMonthlyRewardTier(int position, int roleDurationDays, int coins) {}
    public record BumpOperationalState(LocalDateTime warningNextAt, LocalDateTime warningLastBumpAt) {}

    /** sourceChannelId updates the canonical processing channel when present; it is retained for old clients. */
    public record BumpWarningRequest(Boolean enabled, String sourceChannelId, String targetChannelId,
                                     List<String> messages) {}
    public record BumpIndividualRewardRequest(@Valid BumpRoleRewardRequest role,
                                              @Valid BumpCoinRewardRequest coins) {}
    public record BumpRoleRewardRequest(Boolean enabled, String roleId, Integer durationMinutes, String message) {}
    public record BumpCoinRewardRequest(Boolean enabled, Integer amount) {}
    public record BumpMonthlyRankingRequest(Boolean enabled, String sourceChannelId, String roleId,
                                            List<@Valid BumpMonthlyRewardTier> rewards) {}

    public record StaffRolesResponse(List<String> roleIds, List<GuildRole> roles) {}
    public record StaffRolesRequest(
        @NotNull List<@NotBlank @Pattern(regexp = "[1-9]\\d*", message = "roleId deve ser numérico e positivo") String> roleIds
    ) {}
    public record PortariaRolesRequest(
        @Pattern(regexp = "[1-9]\\d*") String acessoProvisorioRoleId,
        @Pattern(regexp = "[1-9]\\d*") String visitanteRoleId,
        @Pattern(regexp = "[1-9]\\d*") String maior18RoleId,
        @Pattern(regexp = "[1-9]\\d*") String menor18RoleId
    ) {}

    public record PortariaConfig(
        boolean enabled,
        String acessoProvisorioRoleId, String visitanteRoleId, String maior18RoleId, String menor18RoleId,
        boolean aprovacaoAcessoProvisorioAtivo, int aprovacaoAcessoProvisorioDuracaoDias,
        boolean formularioPortariaAtivo, boolean idadeMinimaContaFichaAtiva, int idadeMinimaContaFichaDias,
        boolean idadeMinimaContaAcessoProvisorioAtiva, int idadeMinimaContaAcessoProvisorioDias,
        boolean idadeMinimaEntradaServidorAtiva, int idadeMinimaEntradaServidorAnos
    ) {}
    public record PortariaEnabledRequest(@NotNull Boolean enabled) {}
    public record PortariaFlow(Integer id, String name, String type, String targetChannelId,
                               String approvedTargetChannelId, String rejectedTargetChannelId,
                               boolean rejectionFeedbackEnabled) {}
    public record PortariaQuestion(Integer id, String label, String placeholder, int position, boolean required) {}
    public record PortariaResponse(PortariaConfig config, List<PortariaFlow> flows, PortariaFlow selectedFlow,
                                   List<PortariaQuestion> questions, boolean configured, boolean ambiguous) {}
    public record PortariaPublicationRequest(
        @NotBlank @Pattern(regexp = "[1-9]\\d*", message = "channelId deve ser numérico e positivo") String channelId,
        @Size(max = 2000) String message, @Size(max = 80) String buttonText
    ) { public PortariaPublicationRequest(String channelId) { this(channelId, null, null); } }
    public record PortariaPublicationResult(int flowId, String channelId, String messageId,
                                            boolean created, boolean reused, boolean updated) {
        public PortariaPublicationResult(int flowId, String channelId, String messageId, boolean created, boolean reused) { this(flowId, channelId, messageId, created, reused, false); }
    }

    public record EntryUpdateRequest(String acessoProvisorioRoleId, String visitanteRoleId,
                                     String maior18RoleId, String menor18RoleId,
                                     boolean aprovacaoAcessoProvisorioAtivo,
                                     @Min(1) int aprovacaoAcessoProvisorioDuracaoDias,
                                     boolean idadeMinimaContaFichaAtiva, @Min(0) int idadeMinimaContaFichaDias,
                                     boolean idadeMinimaContaAcessoProvisorioAtiva,
                                     @Min(0) int idadeMinimaContaAcessoProvisorioDias,
                                     boolean idadeMinimaEntradaServidorAtiva,
                                     @Min(0) int idadeMinimaEntradaServidorAnos) {}
    public record CreateFlowRequest(@NotBlank @Size(max = 255) String name, @NotBlank String targetChannelId) {}
    public record ResultUpdateRequest(String targetChannelId, String approvedTargetChannelId, String rejectedTargetChannelId,
                                      boolean rejectionFeedbackEnabled) {}
    public record QuestionRequest(@NotBlank @Size(max = 4000) String label,
                                  @Size(max = 100) String placeholder, boolean required) {}
    public record FormUpdateRequest(boolean formularioPortariaAtivo,
                                    @NotEmpty @Size(max = 5) List<@Valid QuestionRequest> questions) {}
    public record StructurePreviewRequest(@NotBlank String type, String visitorRoleName, String categoryName,
                                          List<String> channelNames, List<String> staffRoleIds,
                                          List<String> allowedRoleIds, boolean isolateVisitors) {}
    public record StructurePreviewResponse(JsonNode preview) {}
    public record GuildOperationRequest(@NotBlank String operation, String visitorRoleName, String categoryName,
                                        List<String> channelNames, List<String> allowedRoleIds,
                                        List<String> staffAccessRoleIds,
                                        Boolean createApproved, Boolean createRejected, String visitorRoleId,
                                        String welcomeChannelId, String targetChannelId,
                                        String approvedTargetChannelId, String rejectedTargetChannelId,
                                        Integer flowId, List<String> channelIds, List<String> entryChannelIds,
                                        String rolePurpose, String channelPurpose, String categoryId) {
        public GuildOperationRequest(String operation, String visitorRoleName, String categoryName, List<String> channelNames,
                                     List<String> allowedRoleIds, List<String> staffAccessRoleIds, Boolean createApproved,
                                     Boolean createRejected, String visitorRoleId, String welcomeChannelId, String targetChannelId,
                                     String approvedTargetChannelId, String rejectedTargetChannelId, Integer flowId,
                                     List<String> channelIds, List<String> entryChannelIds) {
            this(operation, visitorRoleName, categoryName, channelNames, allowedRoleIds, staffAccessRoleIds, createApproved,
                createRejected, visitorRoleId, welcomeChannelId, targetChannelId, approvedTargetChannelId, rejectedTargetChannelId,
                flowId, channelIds, entryChannelIds, null, null, null);
        }
    }
    public record DeleteFlowResult(int flowId, int deletedPublications, boolean portariaConfigured, List<String> warnings) {}
}
