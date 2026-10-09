package com.Brafurries.API.repository.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCustomRole;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserCustomRoleRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

    @Autowired UserRepository users;
    @Autowired UserDiscordRepository discordUsers;
    @Autowired UserCustomRoleRepository customRoles;
    @Autowired CommunityRepository communities;
    @Autowired CommunityDiscordRepository communityDiscords;

    @Test
    void resolvesAllDiscordAccountsForOneInternalUserWithoutCrossCommunityLeakage() {
        User targetUser = user("target");
        User otherUser = user("other");
        UserDiscord firstAccount = discord(targetUser, 1001L, "target-one");
        UserDiscord secondAccount = discord(targetUser, 1002L, "target-two");
        UserDiscord otherAccount = discord(otherUser, 2001L, "other");

        CommunityDiscord targetGuild = guild("Target", 3001L);
        CommunityDiscord otherGuild = guild("Other", 3002L);

        UserCustomRole first = customRole(targetGuild, firstAccount.getDiscordUserId(), 4001L);
        UserCustomRole second = customRole(targetGuild, secondAccount.getDiscordUserId(), 4002L);
        customRole(targetGuild, otherAccount.getDiscordUserId(), 4999L);
        customRole(otherGuild, firstAccount.getDiscordUserId(), 5001L);

        List<UserCustomRole> result = customRoles.findAllByOwnerIdentityAndCommunityOrderByIdDesc(
            targetUser.getId(),
            targetGuild.getCommunity().getId()
        );

        assertEquals(List.of(second.getId(), first.getId()), result.stream().map(UserCustomRole::getId).toList());
    }

    private User user(String name) {
        User user = new User();
        user.setDisplayName(name);
        return users.saveAndFlush(user);
    }

    private UserDiscord discord(User user, long discordUserId, String username) {
        UserDiscord discord = new UserDiscord();
        discord.setUser(user);
        discord.setDiscordUserId(discordUserId);
        discord.setUsername(username);
        discord.setDisplayName(username);
        return discordUsers.saveAndFlush(discord);
    }

    private CommunityDiscord guild(String name, long guildId) {
        Community community = new Community();
        community.setName(name);
        community = communities.saveAndFlush(community);

        CommunityDiscord discord = new CommunityDiscord();
        discord.setCommunity(community);
        discord.setName(name);
        discord.setGuildId(guildId);
        discord.setActive(true);
        discord.setUsersQuantity(0);
        return communityDiscords.saveAndFlush(discord);
    }

    private UserCustomRole customRole(CommunityDiscord guild, long ownerDiscordUserId, long roleId) {
        UserCustomRole role = new UserCustomRole();
        role.setCommunityDiscord(guild);
        role.setOwnerDiscordUserId(ownerDiscordUserId);
        role.setRoleId(roleId);
        role.setColor("123456");
        return customRoles.saveAndFlush(role);
    }
}
