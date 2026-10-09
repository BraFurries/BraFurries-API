package com.Brafurries.API.admin;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserLevel;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserCustomRoleRepository;
import com.Brafurries.API.repository.user.UserEconomyRepository;
import com.Brafurries.API.repository.user.UserInventoryRepository;
import com.Brafurries.API.repository.user.UserLevelRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTempRoleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserCommunityContextServiceTest {

    @Mock UserRepository users;
    @Mock CommunityRepository communities;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserLevelRepository levels;
    @Mock UserEconomyRepository economy;
    @Mock UserInventoryRepository inventory;
    @Mock UserCustomRoleRepository customRoles;
    @Mock UserTempRoleRepository temporaryRoles;

    @Test
    void preservesDuplicateMembershipsAndRefusesToChooseAmbiguousXp() {
        Community community = new Community();
        community.setId(5);
        community.setName("BraFurries");
        UserCommunityStatus first = membership(10, community, true);
        UserCommunityStatus duplicate = membership(11, community, null);

        when(users.existsById(1)).thenReturn(true);
        when(communities.findById(5)).thenReturn(Optional.of(community));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(1, 5))
            .thenReturn(List.of(first, duplicate));
        when(levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(1, 5))
            .thenReturn(List.of(new UserLevel(), new UserLevel()));

        var response = service().getContext(1, 5);

        assertEquals(2, response.memberships().size());
        assertEquals("ambiguous", response.progression().state());
        assertEquals(2, response.progression().recordCount());
        assertNull(response.progression().totalXp());
        assertEquals("not_found", response.economy().state());
    }

    @Test
    void returns404WhenUserHasNoKnownCommunityLink() {
        Community community = new Community();
        community.setId(5);
        when(users.existsById(1)).thenReturn(true);
        when(communities.findById(5)).thenReturn(Optional.of(community));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service().getContext(1, 5));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void exposesTheExistingProgressionBreakdownWithoutRecalculatingIt() {
        Community community = new Community();
        community.setId(5);
        community.setName("BraFurries");
        UserLevel level = new UserLevel();
        level.setCurrentLevel(8);
        level.setTotalXp(4200L);
        level.setDailyRecsCount(3);
        level.setWeeklyBumpXp(250);
        level.setXpAwardedToday(90);
        level.setXpAwardedDay(LocalDate.of(2026, 9, 25));
        level.setXpAwardedTextToday(50);
        level.setXpAwardedTextDay(LocalDate.of(2026, 9, 25));
        level.setXpAwardedVoiceToday(40);
        level.setXpAwardedVoiceDay(LocalDate.of(2026, 9, 25));

        when(users.existsById(1)).thenReturn(true);
        when(communities.findById(5)).thenReturn(Optional.of(community));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(1, 5))
            .thenReturn(List.of(membership(10, community, true)));
        when(levels.findAllByUserIdAndCommunityDiscordCommunityIdOrderByIdAsc(1, 5)).thenReturn(List.of(level));

        var progression = service().getContext(1, 5).progression();

        assertEquals("available", progression.state());
        assertEquals(90, progression.totalXpToday());
        assertEquals("2026-09-25", progression.totalXpDay());
        assertEquals(3, progression.dailyRecsCount());
        assertEquals(250, progression.weeklyBumpXp());
    }

    private UserCommunityStatus membership(int id, Community community, Boolean present) {
        UserCommunityStatus status = new UserCommunityStatus();
        status.setId(id);
        status.setCommunity(community);
        status.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));
        status.setIsPresent(present);
        return status;
    }

    private AdminUserCommunityContextService service() {
        return new AdminUserCommunityContextService(
            users, communities, memberships, levels, economy, inventory, customRoles, temporaryRoles
        );
    }
}
