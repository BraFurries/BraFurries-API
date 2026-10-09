package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.Brafurries.API.user.dto.LogConfigurationDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}")
public class GuildManagementController {
    private final GuildManagementService service;
    private final LogConfigurationService logs;
    private final ModerationAccessService moderationAccess;

    public GuildManagementController(
        GuildManagementService service,
        LogConfigurationService logs,
        ModerationAccessService moderationAccess
    ) {
        this.service = service;
        this.logs = logs;
        this.moderationAccess = moderationAccess;
    }

    @GetMapping("/resources") public GuildResources resources(Authentication auth, @PathVariable String guildId) { return service.resources(auth, guildId); }
    @GetMapping("/channels") public ServerChannelsConfig serverChannels(Authentication auth, @PathVariable String guildId) { return service.serverChannels(auth, guildId); }
    @PutMapping("/channels/birthday") public ServerChannelsConfig updateBirthdayChannel(Authentication auth, @PathVariable String guildId, @RequestBody(required=false) BirthdayChannelRequest request) { return service.updateBirthdayChannel(auth, guildId, request); }
    @PutMapping("/channels/ai") public ServerChannelsConfig updateAiChannels(Authentication auth, @PathVariable String guildId, @RequestBody @Valid AiChannelsRequest request) { return service.updateAiChannels(auth, guildId, request); }
    @PutMapping("/channels/disboard-bump-processing") public ServerChannelsConfig updateBumpProcessingChannel(Authentication auth, @PathVariable String guildId, @RequestBody BumpProcessingChannelRequest request) { return service.updateBumpProcessingChannel(auth, guildId, request); }
    @GetMapping("/bump") public BumpConfigResponse bump(Authentication auth, @PathVariable String guildId) { return service.bump(auth, guildId); }
    @PutMapping("/bump/processing") public BumpConfigResponse updateBumpProcessing(Authentication auth, @PathVariable String guildId, @RequestBody BumpProcessingChannelRequest request) { return service.updateBumpProcessing(auth, guildId, request); }
    @PutMapping("/bump/warning") public BumpConfigResponse updateBumpWarning(Authentication auth, @PathVariable String guildId, @RequestBody @Valid BumpWarningRequest request) { return service.updateBumpWarning(auth, guildId, request); }
    @PutMapping("/bump/individual-reward") public BumpConfigResponse updateBumpIndividualReward(Authentication auth, @PathVariable String guildId, @RequestBody @Valid BumpIndividualRewardRequest request) { return service.updateBumpIndividualReward(auth, guildId, request); }
    @PutMapping("/bump/monthly-ranking") public BumpConfigResponse updateBumpMonthlyRanking(Authentication auth, @PathVariable String guildId, @RequestBody @Valid BumpMonthlyRankingRequest request) { return service.updateBumpMonthlyRanking(auth, guildId, request); }
    @GetMapping("/staff-roles") public StaffRolesResponse staffRoles(Authentication auth, @PathVariable String guildId) { return service.staffRoles(auth, guildId); }
    @PutMapping("/staff-roles")
    public StaffRolesResponse updateStaffRoles(
        Authentication auth,
        @PathVariable String guildId,
        @RequestBody @Valid StaffRolesRequest request
    ) {
        return moderationAccess.updateStaff(auth, guildId, request).staffRoles();
    }
    @GetMapping("/vip-roles") public StaffRolesResponse vipRoles(Authentication auth, @PathVariable String guildId) { return service.vipRoles(auth, guildId); }
    @PutMapping("/vip-roles") public StaffRolesResponse updateVipRoles(Authentication auth, @PathVariable String guildId, @RequestBody @Valid StaffRolesRequest request) { return service.updateVipRoles(auth, guildId, request); }
    @PatchMapping("/portaria/roles") public PortariaConfig updatePortariaRoles(Authentication auth, @PathVariable String guildId, @RequestBody @Valid PortariaRolesRequest request) { return service.updatePortariaRoles(auth, guildId, request); }
    @GetMapping("/portaria") public PortariaResponse portaria(Authentication auth, @PathVariable String guildId, @RequestParam(required=false) Integer flowId) { return service.portaria(auth, guildId, flowId); }
    @PatchMapping("/portaria/enabled") public PortariaResponse updatePortariaEnabled(Authentication auth, @PathVariable String guildId, @RequestBody @Valid PortariaEnabledRequest request) { return service.updatePortariaEnabled(auth, guildId, request); }
    @GetMapping("/auto-join-roles") public AutoJoinRolesResponse autoJoin(Authentication auth, @PathVariable String guildId) { return service.autoJoin(auth, guildId); }
    @PostMapping("/auto-join-roles/{roleId}") public AutoJoinRolesResponse addAutoJoin(Authentication auth, @PathVariable String guildId, @PathVariable String roleId) { return service.addAutoJoin(auth, guildId, roleId); }
    @DeleteMapping("/auto-join-roles/{roleId}") public AutoJoinRolesResponse removeAutoJoin(Authentication auth, @PathVariable String guildId, @PathVariable String roleId) { return service.removeAutoJoin(auth, guildId, roleId); }
    @PatchMapping("/auto-join-roles") public AutoJoinRolesResponse updateAutoJoin(Authentication auth, @PathVariable String guildId, @RequestBody @Valid AutoJoinEnabledRequest request) { return service.updateAutoJoin(auth, guildId, request.enabled()); }
    @GetMapping("/logs") public LogsState logs(Authentication auth, @PathVariable String guildId) { return logs.get(auth, guildId); }
    @PutMapping("/logs/{logType}") public LogsState updateLog(Authentication auth, @PathVariable String guildId, @PathVariable String logType, @RequestBody @Valid UpdateLogRequest request) { return logs.update(auth, guildId, logType, request); }
    @PostMapping("/logs/{logType}/test") public void testLog(Authentication auth, @PathVariable String guildId, @PathVariable String logType, @RequestBody @Valid TestLogRequest request) { logs.test(auth, guildId, logType, request); }
    @PostMapping("/portaria/flows") public PortariaResponse createFlow(Authentication auth, @PathVariable String guildId, @RequestBody @Valid CreateFlowRequest request) { return service.createFlow(auth, guildId, request); }
    @PostMapping("/portaria/flows/{flowId}/publication") public PortariaPublicationResult publishFlow(Authentication auth, @PathVariable String guildId, @PathVariable int flowId, @RequestBody @Valid PortariaPublicationRequest request) { return service.publishPortariaFlow(auth, guildId, flowId, request); }
    @DeleteMapping("/portaria/flows/{flowId}") public DeleteFlowResult deleteFlow(Authentication auth, @PathVariable String guildId, @PathVariable int flowId) { return service.deleteFlow(auth, guildId, flowId); }
    @PatchMapping("/portaria/entry") public PortariaResponse updateEntry(Authentication auth, @PathVariable String guildId, @RequestParam(required=false) Integer flowId, @RequestBody @Valid EntryUpdateRequest request) { return service.updateEntry(auth, guildId, flowId, request); }
    @PatchMapping("/portaria/flows/{flowId}/form") public PortariaResponse updateForm(Authentication auth, @PathVariable String guildId, @PathVariable int flowId, @RequestBody @Valid FormUpdateRequest request) { return service.updateForm(auth, guildId, flowId, request); }
    @PatchMapping("/portaria/flows/{flowId}/result") public PortariaResponse updateResult(Authentication auth, @PathVariable String guildId, @PathVariable int flowId, @RequestBody @Valid ResultUpdateRequest request) { return service.updateResult(auth, guildId, flowId, request); }
    @PostMapping("/structure-preview") public JsonNode preview(Authentication auth, @PathVariable String guildId, @RequestBody @Valid StructurePreviewRequest request) { return service.preview(auth, guildId, request); }
    @PostMapping("/operations") public JsonNode operation(Authentication auth, @PathVariable String guildId, @RequestBody @Valid GuildOperationRequest request) { return service.operation(auth, guildId, request); }
}
