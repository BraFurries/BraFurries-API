package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminUserItem;
import com.Brafurries.API.admin.dto.AdminDtos.AdminUsersResponse;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserRoleRequest;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserStatusRequest;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.UserReliabilityResponse;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.AdminUserProfileResponse;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.CommunityContextResponse;
import com.Brafurries.API.admin.dto.AdminDiscordMemberDtos.MemberState;
import com.Brafurries.API.admin.dto.UserIdentityDtos.CreateIdentityLinkRequest;
import com.Brafurries.API.admin.dto.UserIdentityDtos.IdentityLinkView;
import com.Brafurries.API.admin.dto.UserIdentityDtos.ModerationHistoryResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.RevokeIdentityLinkRequest;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UpdateIdentityLinkRequest;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserIdentityResponse;
import com.Brafurries.API.common.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Administracao de usuarios",
    description = "Endpoints administrativos para consultar, auditar e gerenciar usuarios da plataforma"
)
public class AdminUserController {

    private final AdminUserReliabilityService adminUserReliabilityService;
    private final AdminUsersService adminUsersService;
    private final AdminUserProfileService adminUserProfileService;
    private final UserIdentityLinkService userIdentityLinkService;
    private final UserIdentityModerationService userIdentityModerationService;
    private final AdminUserCommunityContextService adminUserCommunityContextService;
    private final AdminDiscordMemberService adminDiscordMemberService;

    public AdminUserController(
        AdminUserReliabilityService adminUserReliabilityService,
        AdminUsersService adminUsersService,
        AdminUserProfileService adminUserProfileService,
        UserIdentityLinkService userIdentityLinkService,
        UserIdentityModerationService userIdentityModerationService,
        AdminUserCommunityContextService adminUserCommunityContextService,
        AdminDiscordMemberService adminDiscordMemberService
    ) {
        this.adminUserReliabilityService = adminUserReliabilityService;
        this.adminUsersService = adminUsersService;
        this.adminUserProfileService = adminUserProfileService;
        this.userIdentityLinkService = userIdentityLinkService;
        this.userIdentityModerationService = userIdentityModerationService;
        this.adminUserCommunityContextService = adminUserCommunityContextService;
        this.adminDiscordMemberService = adminDiscordMemberService;
    }

    @GetMapping("/{userId}/identity")
    public UserIdentityResponse getIdentity(@PathVariable Integer userId) {
        return userIdentityLinkService.getIdentity(userId);
    }

    @PostMapping("/{userId}/identity-links")
    public IdentityLinkView createIdentityLink(
        Authentication authentication,
        @PathVariable Integer userId,
        @Valid @RequestBody CreateIdentityLinkRequest request
    ) {
        return userIdentityLinkService.create(
            userId, request.otherUserId(), request.status(), request.reason(), authentication.getName()
        );
    }

    @PatchMapping("/{userId}/identity-links/{otherUserId}")
    public IdentityLinkView updateIdentityLink(
        @PathVariable Integer userId,
        @PathVariable Integer otherUserId,
        @Valid @RequestBody UpdateIdentityLinkRequest request
    ) {
        return userIdentityLinkService.update(userId, otherUserId, request.status(), request.reason());
    }

    @PostMapping("/{userId}/identity-links/{otherUserId}/revoke")
    public IdentityLinkView revokeIdentityLink(
        Authentication authentication,
        @PathVariable Integer userId,
        @PathVariable Integer otherUserId,
        @Valid @RequestBody RevokeIdentityLinkRequest request
    ) {
        return userIdentityLinkService.revoke(userId, otherUserId, request.reason(), authentication.getName());
    }

    @GetMapping("/{userId}/moderation-history")
    public ModerationHistoryResponse getModerationHistory(
        @PathVariable Integer userId,
        @RequestParam Integer communityId
    ) {
        return userIdentityModerationService.getHistory(userId, communityId);
    }

    @Operation(summary = "Consulta ficha administrativa de usuário")
    @GetMapping("/{id}/profile")
    public AdminUserProfileResponse getUserProfile(
        @PathVariable Integer id,
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        return adminUserProfileService.getProfile(id, page, pageSize);
    }

    @Operation(summary = "Consulta dados persistidos do usuário em uma comunidade")
    @GetMapping("/{id}/communities/{communityId}/context")
    public CommunityContextResponse getUserCommunityContext(
        @PathVariable Integer id,
        @PathVariable Integer communityId
    ) {
        return adminUserCommunityContextService.getContext(id, communityId);
    }

    @Operation(summary = "Consulta o estado ao vivo do membro no Discord via Coddy")
    @GetMapping("/{id}/communities/{communityId}/discord-member")
    public MemberState getDiscordMember(
        @PathVariable Integer id,
        @PathVariable Integer communityId
    ) {
        return adminDiscordMemberService.getMember(id, communityId);
    }

    @Operation(summary = "Lista usuarios para telas administrativas")
    @GetMapping
    public AdminUsersResponse listUsers(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String role
    ) {
        return adminUsersService.listUsers(page, pageSize, search, status, role);
    }

    @Operation(summary = "Atualiza status administrativo de usuario")
    @PatchMapping("/{id}/status")
    public AdminUserItem updateUserStatus(
        @PathVariable Integer id,
        @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return adminUsersService.updateStatus(id, request);
    }

    @Operation(summary = "Atualiza cargo administrativo de usuario")
    @PatchMapping("/{id}/role")
    public AdminUserItem updateUserRole(
        @PathVariable Integer id,
        @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        return adminUsersService.updateRole(id, request);
    }

    @Operation(
        summary = "Consulta confiabilidade de usuario",
        description = """
            Retorna um dossie administrativo de confiabilidade do usuario.

            A consulta pode ser feita por id ou e-mail e agrega sinais de moderacao,
            participacao em comunidades, vinculos externos e historico de eventos.
            Este endpoint permanece como rota de busca flexivel para telas administrativas.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Informacoes de confiabilidade retornadas com sucesso"),
        @ApiResponse(
            responseCode = "400",
            description = "Nenhum identificador informado ou parametro invalido",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Requisicao nao autenticada",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Usuario autenticado nao possui permissao administrativa",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Usuario nao encontrado",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        )
    })
    @GetMapping("/reliability")
    public UserReliabilityResponse getUserReliability(
        @Parameter(description = "ID interno do usuario", example = "123")
        @RequestParam(required = false) Integer id,
        @Parameter(description = "E-mail cadastrado do usuario. Usado quando id nao for informado.", example = "usuario@email.com")
        @RequestParam(required = false) String email
    ) {
        return adminUserReliabilityService.getUserReliability(id, email);
    }

    @Operation(
        summary = "Consulta confiabilidade de usuario por ID",
        description = """
            Retorna o mesmo dossie administrativo de confiabilidade usando uma rota
            canonica baseada no identificador interno do usuario.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Informacoes de confiabilidade retornadas com sucesso"),
        @ApiResponse(
            responseCode = "401",
            description = "Requisicao nao autenticada",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Usuario autenticado nao possui permissao administrativa",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Usuario nao encontrado",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
        )
    })
    @GetMapping("/{id}/reliability")
    public UserReliabilityResponse getUserReliabilityById(
        @Parameter(description = "ID interno do usuario", example = "123", required = true)
        @PathVariable Integer id
    ) {
        return adminUserReliabilityService.getUserReliability(id, null);
    }
}
