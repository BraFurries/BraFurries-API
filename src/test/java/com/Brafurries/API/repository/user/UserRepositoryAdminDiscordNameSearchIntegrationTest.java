package com.Brafurries.API.repository.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class UserRepositoryAdminDiscordNameSearchIntegrationTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

    @Autowired UserRepository users;
    @Autowired UserDiscordRepository discord;

    @Test
    void findsDiscordNamesWhenUsersTableDoesNotContainIdentityNames() {
        // This is how lifecycle-created identities are persisted: the global
        // User names are null; the Discord identity contains the searchable names.
        User first = users.saveAndFlush(new User());
        link(first, 223456789012345678L, "dve0730", "Dve Alt");

        User second = users.saveAndFlush(new User());
        link(second, 323456789012345678L, "unrelated", "dve70_70 reserva");

        User unmatched = users.saveAndFlush(new User());
        link(unmatched, 423456789012345678L, "other.user", "Other User");

        var firstPage = users.searchAdminUsersFiltered(
            "DVE", null, null, null, LocalDate.now(),
            PageRequest.of(0, 1, Sort.by("id"))
        );
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(first.getId(), firstPage.getContent().getFirst().getId());

        var secondPage = users.searchAdminUsersFiltered(
            "dve", null, null, null, LocalDate.now(),
            PageRequest.of(1, 1, Sort.by("id"))
        );
        assertEquals(2, secondPage.getTotalElements());
        assertEquals(second.getId(), secondPage.getContent().getFirst().getId());

        var missing = users.searchAdminUsersFiltered(
            "othercommunity", null, null, null, LocalDate.now(),
            PageRequest.of(0, 20)
        );
        assertEquals(0, missing.getTotalElements());
    }

    @Test
    void keepsExactSnowflakeMatchAndDoesNotDuplicateUsersWithMultipleDiscordLinks() {
        User user = users.saveAndFlush(new User());
        link(user, 523456789012345678L, "match.user", "Visible Match");
        link(user, 623456789012345678L, "match.alt", "Another Match");

        var textResult = users.searchAdminUsersFiltered(
            "match", null, null, null, LocalDate.now(), PageRequest.of(0, 20)
        );
        assertEquals(1, textResult.getTotalElements());
        assertEquals(user.getId(), textResult.getContent().getFirst().getId());

        var idResult = users.searchAdminUsersFiltered(
            null, 523456789012345678L, null, null, LocalDate.now(),
            PageRequest.of(0, 20)
        );
        assertEquals(1, idResult.getTotalElements());
        assertEquals(user.getId(), idResult.getContent().getFirst().getId());
    }

    private void link(User user, long discordId, String username, String displayName) {
        UserDiscord identity = new UserDiscord();
        identity.setUser(user);
        identity.setDiscordUserId(discordId);
        identity.setUsername(username);
        identity.setDisplayName(displayName);
        discord.saveAndFlush(identity);
    }
}
