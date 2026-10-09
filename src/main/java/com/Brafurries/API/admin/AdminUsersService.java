package com.Brafurries.API.admin;

import static com.Brafurries.API.config.CacheConfig.ADMIN_OVERVIEW;
import static com.Brafurries.API.config.CacheConfig.ADMIN_SESSION;
import static com.Brafurries.API.config.CacheConfig.ADMIN_USERS;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPagination;
import com.Brafurries.API.admin.dto.AdminDtos.AdminUserItem;
import com.Brafurries.API.admin.dto.AdminDtos.AdminUsersResponse;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserRoleRequest;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserStatusRequest;
import com.Brafurries.API.entity.api.ApiUserRole;
import com.Brafurries.API.entity.api.ApiUserRole.ApiUserRoleId;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.api.ApiRoleRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUsersService {

    private static final Set<String> SUPPORTED_ROLES = Set.of("member", "supporter", "moderator", "admin");
    private static final Set<String> SUPPORTED_STATUS = Set.of("active", "muted", "banned");
    private static final Set<String> LIST_SUPPORTED_STATUS = Set.of("active", "banned");

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final UserBanRepository userBanRepository;
    private final ApiRoleRepository apiRoleRepository;
    private final ApiUserRoleRepository apiUserRoleRepository;

    public AdminUsersService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        UserBanRepository userBanRepository,
        ApiRoleRepository apiRoleRepository,
        ApiUserRoleRepository apiUserRoleRepository
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.userBanRepository = userBanRepository;
        this.apiRoleRepository = apiRoleRepository;
        this.apiUserRoleRepository = apiUserRoleRepository;
    }

    @Cacheable(
        cacheNames = ADMIN_USERS,
        key = "#page + ':' + #pageSize + ':' + (#search == null ? '' : #search.trim().toLowerCase()) + ':' + (#status == null ? '' : #status.trim().toLowerCase()) + ':' + (#role == null ? '' : #role.trim().toLowerCase())"
    )
    public AdminUsersResponse listUsers(int page, int pageSize, String search, String status, String role) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.clamp(pageSize, 1, 100);
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        if (normalizedSearch != null && normalizedSearch.startsWith("@") && normalizedSearch.length() > 1) {
            normalizedSearch = normalizedSearch.substring(1);
        }
        boolean discordSnowflakeSearch = isDiscordSnowflakeSearch(normalizedSearch);
        Long discordUserId = discordSnowflakeSearch ? parseDiscordSnowflake(normalizedSearch) : null;
        String textSearch = discordSnowflakeSearch ? null : normalizedSearch;
        String normalizedStatus = normalizeFilter(status, LIST_SUPPORTED_STATUS, "Status invalido para listagem");
        String normalizedRole = normalizeFilter(role, SUPPORTED_ROLES, "Cargo invalido");

        var usersPage = userRepository.searchAdminUsersFiltered(
            textSearch,
            discordUserId,
            normalizedStatus,
            normalizedRole,
            LocalDate.now(),
            PageRequest.of(normalizedPage - 1, normalizedPageSize, Sort.by(Sort.Direction.ASC, "id"))
        );

        List<User> users = usersPage.getContent();
        Collection<Integer> userIds = users.stream().map(User::getId).toList();
        if (userIds.isEmpty()) {
            return new AdminUsersResponse(List.of(), new AdminPagination(
                normalizedPage,
                normalizedPageSize,
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
            ));
        }

        Map<Integer, List<UserDiscord>> discordByUser = userDiscordRepository.findByUserIdIn(userIds)
            .stream()
            .collect(Collectors.groupingBy(ud -> ud.getUser().getId()));

        Map<Integer, String> telegramByUser = userTelegramRepository.findByUserIdIn(userIds)
            .stream()
            .collect(Collectors.toMap(
                ut -> ut.getUser().getId(),
                ut -> coalesce(ut.getDisplayName(), ut.getUsername()),
                (a, b) -> a
            ));

        Map<Integer, Long> activeBansByUser = userBanRepository.countActiveBansByUserIds(userIds, LocalDate.now())
            .stream()
            .collect(Collectors.toMap(
                UserBanRepository.ActiveBanCountProjection::getUserId,
                UserBanRepository.ActiveBanCountProjection::getTotal
            ));

        List<AdminUserItem> items = users.stream()
            .map(user -> toItem(user, discordByUser, telegramByUser, activeBansByUser))
            .toList();

        return new AdminUsersResponse(items, new AdminPagination(
            normalizedPage,
            normalizedPageSize,
            usersPage.getTotalElements(),
            usersPage.getTotalPages()
        ));
    }

    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_USERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true)
    })
    public AdminUserItem updateStatus(Integer id, UpdateUserStatusRequest request) {
        String status = normalize(request.status());
        if (!SUPPORTED_STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status invalido");
        }
        if (!"active".equals(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Status de mutar/banir exige dados de moderacao por comunidade e ainda nao pode ser persistido por este endpoint"
            );
        }
        User user = findUser(id);
        return toItem(user, Map.of(), Map.of(), Map.of());
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_USERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_SESSION, allEntries = true)
    })
    public AdminUserItem updateRole(Integer id, UpdateUserRoleRequest request) {
        String roleName = normalize(request.role());
        if (!SUPPORTED_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cargo invalido");
        }
        User user = findUser(id);

        apiUserRoleRepository.deleteAdminScreenRolesByUserId(id, SUPPORTED_ROLES);
        if (!"member".equals(roleName)) {
            var role = apiRoleRepository.findByNameIgnoreCase(roleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cargo nao cadastrado"));
            ApiUserRole userRole = new ApiUserRole();
            ApiUserRoleId userRoleId = new ApiUserRoleId();
            userRoleId.setUserId(id);
            userRoleId.setRoleId(role.getId());
            userRole.setId(userRoleId);
            userRole.setUser(user);
            userRole.setRole(role);
            apiUserRoleRepository.save(userRole);
        }
        return toItem(user, Map.of(), Map.of(), Map.of());
    }

    public String resolvePrimaryRole(Integer userId) {
        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(userId)
            .stream()
            .map(AdminUsersService::normalize)
            .toList();
        if (roles.contains("admin")) {
            return "admin";
        }
        if (roles.contains("moderator")) {
            return "moderator";
        }
        if (roles.contains("supporter")) {
            return "supporter";
        }
        return "member";
    }

    private AdminUserItem toItem(
        User user,
        Map<Integer, List<UserDiscord>> discordByUser,
        Map<Integer, String> telegramByUser,
        Map<Integer, Long> activeBansByUser
    ) {
        List<UserDiscord> discordAccounts = discordByUser.getOrDefault(user.getId(), List.of());
        boolean discordAmbiguous = discordAccounts.size() > 1;
        String discordUsername = discordAccounts.size() == 1
            ? coalesce(discordAccounts.getFirst().getUsername())
            : null;

        String status = activeBansByUser.getOrDefault(user.getId(), 0L) > 0 ? "banned" : "active";
        return new AdminUserItem(
            user.getId(),
            coalesce(user.getDisplayName(), user.getUsername(), user.getEmail(), "Usuario " + user.getId()),
            user.getProfileImageUrl(),
            user.getEmail(),
            discordUsername,
            discordAmbiguous,
            telegramByUser.get(user.getId()),
            status,
            resolvePrimaryRole(user.getId()),
            null
        );
    }

    private User findUser(Integer id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario nao encontrado"));
    }

    private String normalizeFilter(String value, Set<String> supportedValues, String errorMessage) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalize(value);
        if (!supportedValues.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDiscordSnowflakeSearch(String search) {
        return search != null && search.matches("[0-9]{15,20}");
    }

    private Long parseDiscordSnowflake(String search) {
        try {
            return Long.parseLong(search);
        } catch (NumberFormatException ignored) {
            // user_discord.discord_user_id is a signed BIGINT/Long; an out-of-range
            // numeric value is still an exact snowflake-shaped search with no match.
            return Long.MIN_VALUE;
        }
    }

    private static String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
