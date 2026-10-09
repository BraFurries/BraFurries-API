package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.UserDashboardDtos.UserDashboardResponse;
import com.Brafurries.API.user.dto.UserGeneralInfoDtos.UserGeneralInfoResponse;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileRequest;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileResponse;
import com.Brafurries.API.user.dto.UserPermissionDtos.UserPermissionResponse;
import com.Brafurries.API.user.dto.UserProfileDtos.UserProfileImageResponse;
import com.Brafurries.API.user.dto.UserProfileDtos.UserProfileResponse;
import com.Brafurries.API.user.dto.UserManagedServerDtos.ManagedServerResponse;
import com.Brafurries.API.user.dto.MemberProfileDtos.MemberProfileResponse;
import com.Brafurries.API.user.dto.UserCommunityContextDtos.MemberCommunityContextResponse;
import com.Brafurries.API.user.dto.UserModerationDtos.MemberModerationPage;
import java.util.List;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("")
@Tag(name = "Usuário", description = "Endpoints gerais do usuário autenticado")
public class UserController {

    private final UserProfileService userProfileService;
    private final UserPermissionService userPermissionService;
    private final UserGeneralInfoService userGeneralInfoService;
    private final UserProfileImageService userProfileImageService;
    private final UserDashboardService userDashboardService;
    private final ManagedServersService managedServersService;
    private final MemberProfileService memberProfileService;
    private final UserCommunityContextService userCommunityContextService;
    private final UserModerationHistoryService userModerationHistoryService;
    private final MemberProfileUpdateService memberProfileUpdateService;

    public UserController(
        UserProfileService userProfileService,
        UserPermissionService userPermissionService,
        UserGeneralInfoService userGeneralInfoService,
        UserProfileImageService userProfileImageService,
        UserDashboardService userDashboardService,
        ManagedServersService managedServersService,
        MemberProfileService memberProfileService,
        UserCommunityContextService userCommunityContextService,
        UserModerationHistoryService userModerationHistoryService,
        MemberProfileUpdateService memberProfileUpdateService
    ) {
        this.userProfileService = userProfileService;
        this.userPermissionService = userPermissionService;
        this.userGeneralInfoService = userGeneralInfoService;
        this.userProfileImageService = userProfileImageService;
        this.userDashboardService = userDashboardService;
        this.managedServersService = managedServersService;
        this.memberProfileService = memberProfileService;
        this.userCommunityContextService = userCommunityContextService;
        this.userModerationHistoryService = userModerationHistoryService;
        this.memberProfileUpdateService = memberProfileUpdateService;
    }

    @Operation(summary = "Retorna o perfil do usuário logado")
    @GetMapping("/user/me")
    public UserProfileResponse getMyProfile(
        Authentication authentication,
        @RequestParam(name = "server", required = false) Long serverGuildId
    ) {
        return userProfileService.getLoggedUserProfile(authentication.getName(), serverGuildId);
    }

    @Operation(summary = "Retorna o perfil real do membro autenticado")
    @GetMapping("/user/me/profile")
    public MemberProfileResponse getMyMemberProfile(Authentication authentication) {
        return memberProfileService.getLoggedUserProfile(authentication.getName());
    }

    @Operation(summary = "Retorna o contexto do membro autenticado em uma comunidade")
    @GetMapping("/user/me/communities/{communityId}/context")
    public MemberCommunityContextResponse getMyCommunityContext(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return userCommunityContextService.getLoggedUserContext(authentication.getName(), communityId);
    }

    @Operation(summary = "Retorna o histórico de moderação do membro autenticado em uma comunidade")
    @GetMapping("/user/me/communities/{communityId}/moderation")
    public MemberModerationPage getMyCommunityModeration(
        Authentication authentication,
        @PathVariable Integer communityId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize
    ) {
        return userModerationHistoryService.getLoggedUserModeration(authentication.getName(), communityId, page, pageSize);
    }

    @Operation(summary = "Retorna as permissões do usuário logado (sistema e servidores onde atua como staff)")
    @GetMapping("/user/me/permissions")
    public UserPermissionResponse getMyPermissions(Authentication authentication) {
        return userPermissionService.getLoggedUserPermissions(authentication);
    }

    @Operation(summary = "Retorna informações gerais do membro logado")
    @GetMapping("/user/me/general-info")
    public UserGeneralInfoResponse getMyGeneralInfo(Authentication authentication) {
        return userGeneralInfoService.getLoggedUserGeneralInfo(authentication.getName());
    }

    @Operation(summary = "Retorna os dados do dashboard do usuário logado")
    @GetMapping("/user/me/dashboard")
    public UserDashboardResponse getMyDashboard(Authentication authentication) {
        return userDashboardService.getLoggedUserDashboard(authentication);
    }

    @Operation(summary = "Retorna os servidores com Coddy que o usuário autenticado pode administrar")
    @GetMapping("/user/me/managed-servers")
    public List<ManagedServerResponse> getMyManagedServers(Authentication authentication) {
        return managedServersService.getLoggedUserManagedServers(authentication);
    }

    @Operation(summary = "Atualiza o nome de exibição global do usuário logado")
    @PatchMapping("/user/me/profile")
    public UpdateMemberProfileResponse updateMyProfile(
        Authentication authentication,
        @Valid @RequestBody UpdateMemberProfileRequest request
    ) {
        return memberProfileUpdateService.updateDisplayName(authentication.getName(), request);
    }

    @Operation(summary = "Atualiza a foto de perfil do usuário logado")
    @PutMapping(value = "/user/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserProfileImageResponse uploadMyProfileImage(
        Authentication authentication,
        @RequestParam("file") MultipartFile file
    ) {
        return userProfileImageService.uploadProfileImage(authentication.getName(), file);
    }

    @Operation(summary = "Remove a foto de perfil do usuário logado")
    @DeleteMapping("/user/me/profile-image")
    public UserProfileImageResponse deleteMyProfileImage(Authentication authentication) {
        return userProfileImageService.deleteProfileImage(authentication.getName());
    }
}
