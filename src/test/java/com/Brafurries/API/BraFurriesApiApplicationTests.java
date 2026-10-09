package com.Brafurries.API;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.repository.event.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.base-key=integration-test-signing-secret"
})
@Sql(statements = {
		"ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_key VARCHAR(512) NULL",
		"ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_url VARCHAR(1024) NULL",
		"ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_content_type VARCHAR(100) NULL",
		"ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_updated_at DATETIME NULL"
})
class BraFurriesApiApplicationTests {

	@Container
	@ServiceConnection
	static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

	@Autowired
	private EventRepository eventRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void findUserEventsListingSupportsPartnerCommonAndManagedScopes() {
		Pageable pageable = PageRequest.of(0, 10);

		assertDoesNotThrow(() -> {
			Page<Event> partner = eventRepository.findUserEventsListing(
					"integration-user@example.com",
					true,
					true,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					"partner",
					null,
					false,
					null,
					LocalDateTime.now(),
					pageable
			);

			Page<Event> common = eventRepository.findUserEventsListing(
					"integration-user@example.com",
					true,
					true,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					"common",
					null,
					false,
					null,
					LocalDateTime.now(),
					pageable
			);

			Page<Event> managed = eventRepository.findUserEventsListing(
					"integration-user@example.com",
					true,
					true,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					null,
					"managed",
					null,
					true,
					null,
					LocalDateTime.now(),
					pageable
			);

			partner.getContent().size();
			common.getContent().size();
			managed.getContent().size();
		});
	}

}
