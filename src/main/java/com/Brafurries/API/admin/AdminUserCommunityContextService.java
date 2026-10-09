package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.CommunityContextResponse;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.CustomRole;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.Economy;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.EconomyRecord;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.InventoryItem;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.MembershipRecord;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.Progression;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.TemporaryRole;
import com.Brafurries.API.admin.dto.AdminUserCommunityContextDtos.Vip;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserEconomy;
import com.Brafurries.API.entity.user.UserLevel;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserCustomRoleRepository;
import com.Brafurries.API.repository.user.UserEconomyRepository;
import com.Brafurries.API.repository.user.UserInventoryRepository;
import com.Brafurries.API.repository.user.UserLevelRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTempRoleRepository;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminUserCommunityContextService {

    private final UserRepository users;
    private final CommunityRepository communities;
    private final UserCommunityStatusRepository memberships;
    private final UserLevelRepository levels;
    private final UserEconomyRepository economy;
    private final UserInventoryRepository inventory;
    private final UserCustomRoleRepository customRoles;
    private final UserTempRoleRepository temporaryRoles;

    public AdminUserCommunityContextService(
        UserRepository users,
        CommunityRepository communities,
        UserCommunityStatusRepository memberships,
        UserLevelRepository levels,
        UserEconomyRepository economy,
        UserInventoryRepository inventory,
        UserCustomRoleRepository customRoles,
        UserTempRoleRepository temporaryRoles
    ) {
        this.users = users;
        this.communities = communities;
        this.memberships = memberships;
        this.levels = levels;
        this.economy = economy;
        this.inventory = inventory;
        this.customRoles = customRoles;
        this.temporaryRoles = temporaryRoles;
    }

    public CommunityContextResponse getContext(Integer userId, Integer communityId) {
        if (!users.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
        Community community = communities.findById(communityId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comunidade não encontrada"));
        List<UserCommunityStatus> statusRows = memberships
            .findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(userId, communityId);
        if (statusRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário sem vínculo conhecido com esta comunidade");
        }

        CommunityDiscord discord = community.getDiscord();
        List<UserLevel> levelRows = levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(userId, communityId);
        List<UserEconomy> economyRows = economy.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(userId, communityId);

        return new CommunityContextResponse(
            userId,
            communityId,
            community.getName(),
            discord == null || discord.getGuildId() == null ? null : String.valueOf(discord.getGuildId()),
            statusRows.stream().map(this::membership).toList(),
            progression(levelRows),
            new Economy(
                state(economyRows.size()),
                economyRows.size(),
                economyRows.stream().map(row -> new EconomyRecord(row.getId(), row.getBankBalance())).toList()
            ),
            inventory.findAllByOwnerUserIdAndCommunityIdOrderByIdDesc(userId, communityId).stream()
                .map(item -> new InventoryItem(
                    item.getId(), item.getStoreItem().getId(), item.getStoreItem().getItemName(), item.getQuantity(),
                    Boolean.TRUE.equals(item.getStoreItem().getIsService()), text(item.getUsedIn()), text(item.getValidUntil())
                )).toList(),
            new Vip(
                consensus(statusRows.stream().map(UserCommunityStatus::getIsVip).toList()),
                customRoles.findAllByOwnerIdentityAndCommunityOrderByIdDesc(userId, communityId).stream()
                    .map(role -> new CustomRole(
                        role.getId(), role.getColor(), role.getColor2(), value(role.getRoleId()), value(role.getIconId())
                    )).toList()
            ),
            temporaryRoles.findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(userId, communityId)
                .stream().map(role -> new TemporaryRole(
                    role.getId(), value(role.getRoleId()), text(role.getExpiringDate()), role.getReason()
                )).toList()
        );
    }

    private MembershipRecord membership(UserCommunityStatus row) {
        return new MembershipRecord(
            row.getId(), text(row.getMemberSince()), text(row.getLastJoinDate()), row.getApproved(), text(row.getApprovedAt()),
            row.getInviteLinkUsed(), row.getInvitedBy(), row.getIsVip(), row.getIsPartner(), row.getBanned(),
            row.getIsPresent(), text(row.getLeftAt()), row.getBirthdayMentionable()
        );
    }

    private Progression progression(List<UserLevel> rows) {
        if (rows.size() != 1) {
            return new Progression(
                state(rows.size()), rows.size(), null, null, null, null, null, null, null, null, null, null
            );
        }
        UserLevel row = rows.getFirst();
        return new Progression(
            "available",
            1,
            row.getCurrentLevel(),
            row.getTotalXp(),
            row.getDailyRecsCount(),
            row.getWeeklyBumpXp(),
            row.getXpAwardedToday(),
            text(row.getXpAwardedDay()),
            row.getXpAwardedTextToday(),
            text(row.getXpAwardedTextDay()),
            row.getXpAwardedVoiceToday(),
            text(row.getXpAwardedVoiceDay())
        );
    }

    private static String state(int count) {
        return count == 0 ? "not_found" : count == 1 ? "available" : "ambiguous";
    }

    private static String text(TemporalAccessor value) {
        return value == null ? null : value.toString();
    }

    private static String value(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean consensus(List<Boolean> values) {
        List<Boolean> known = values.stream().filter(Objects::nonNull).distinct().toList();
        return known.size() == 1 ? known.getFirst() : null;
    }
}
