package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.UserCommunityContextDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserEconomy;
import com.Brafurries.API.entity.user.UserInventory;
import com.Brafurries.API.entity.user.UserLevel;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserEconomyRepository;
import com.Brafurries.API.repository.user.UserInventoryRepository;
import com.Brafurries.API.repository.user.UserLevelRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserCommunityContextService {
    private static final String CONTEXT_NOT_FOUND = "Contexto comunitário não encontrado";
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;
    private final UserLevelRepository userLevelRepository;
    private final UserEconomyRepository userEconomyRepository;
    private final UserInventoryRepository userInventoryRepository;
    private final UserWarningRepository userWarningRepository;
    private final UserBanRepository userBanRepository;

    public UserCommunityContextService(
        UserRepository userRepository,
        UserCommunityStatusRepository userCommunityStatusRepository,
        UserLevelRepository userLevelRepository,
        UserEconomyRepository userEconomyRepository,
        UserInventoryRepository userInventoryRepository,
        UserWarningRepository userWarningRepository,
        UserBanRepository userBanRepository
    ) {
        this.userRepository = userRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
        this.userLevelRepository = userLevelRepository;
        this.userEconomyRepository = userEconomyRepository;
        this.userInventoryRepository = userInventoryRepository;
        this.userWarningRepository = userWarningRepository;
        this.userBanRepository = userBanRepository;
    }

    @Transactional(readOnly = true)
    public MemberCommunityContextResponse getLoggedUserContext(String principal, Integer communityId) {
        User user = findAuthenticatedUser(principal);
        List<UserCommunityStatus> memberships = userCommunityStatusRepository
            .findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(user.getId(), communityId);
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, CONTEXT_NOT_FOUND);
        }

        Community community = memberships.getFirst().getCommunity();
        if (community.getDiscord() == null || !Boolean.TRUE.equals(community.getDiscord().getActive())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, CONTEXT_NOT_FOUND);
        }
        List<UserLevel> levels = userLevelRepository.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(user.getId(), communityId);
        List<UserEconomy> economies = userEconomyRepository.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(user.getId(), communityId);
        List<MemberInventoryItem> inventory = userInventoryRepository
            .findTop6ByOwnerUserIdAndCommunityIdOrderByIdDesc(user.getId(), communityId)
            .stream()
            .map(this::toInventoryItem)
            .toList();

        return new MemberCommunityContextResponse(
            new MemberCommunityRef(community.getId(), community.getName(), community.getDiscord() == null ? null : String.valueOf(community.getDiscord().getGuildId())),
            membership(memberships),
            progression(levels),
            economy(economies),
            inventory,
            new MemberModerationSummary(
                userWarningRepository.countByUserIdAndCommunityId(user.getId(), communityId),
                userWarningRepository.countByUserIdAndCommunityIdAndExpiredFalse(user.getId(), communityId),
                userBanRepository.countByUserIdAndCommunityId(user.getId(), communityId),
                userBanRepository.countActiveBansByUserIdAndCommunityId(user.getId(), communityId, LocalDate.now())
            )
        );
    }

    private User findAuthenticatedUser(String principal) {
        String normalizedEmail = principal == null ? "" : principal.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }

    private MemberMembershipData membership(List<UserCommunityStatus> records) {
        if (records.size() != 1) {
            return new MemberMembershipData(state(records.size()), records.size(), null, null, null, null, null, null, null, null);
        }
        UserCommunityStatus record = records.getFirst();
        return new MemberMembershipData(
            MemberDataState.AVAILABLE, 1, record.getMemberSince(), record.getLastJoinDate(), record.getApproved(), record.getApprovedAt(),
            record.getIsPresent(), record.getLeftAt(), record.getIsVip(), record.getIsPartner()
        );
    }

    private MemberProgressionData progression(List<UserLevel> records) {
        return records.size() == 1
            ? new MemberProgressionData(MemberDataState.AVAILABLE, 1, records.getFirst().getCurrentLevel(), records.getFirst().getTotalXp())
            : new MemberProgressionData(state(records.size()), records.size(), null, null);
    }

    private MemberEconomyData economy(List<UserEconomy> records) {
        return records.size() == 1
            ? new MemberEconomyData(MemberDataState.AVAILABLE, 1, records.getFirst().getBankBalance())
            : new MemberEconomyData(state(records.size()), records.size(), null);
    }

    private MemberInventoryItem toInventoryItem(UserInventory inventory) {
        var item = inventory.getStoreItem();
        return new MemberInventoryItem(inventory.getId(), item.getId(), item.getItemName(), item.getItemImageUrl(), inventory.getQuantity(), item.getIsService(), inventory.getUsedIn(), inventory.getValidUntil());
    }

    private MemberDataState state(int recordCount) {
        return recordCount == 0 ? MemberDataState.NOT_FOUND : recordCount == 1 ? MemberDataState.AVAILABLE : MemberDataState.AMBIGUOUS;
    }
}
