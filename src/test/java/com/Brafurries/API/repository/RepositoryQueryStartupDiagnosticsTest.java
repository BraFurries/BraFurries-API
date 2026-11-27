package com.Brafurries.API.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Diagnostic only: time the real Spring Data query resolution path without
 * executing repository methods, changing production bootstrap, or using
 * production data. No timing assertion: CI hardware and JVM warmup vary.
 */
@Testcontainers
@DataJpaTest(bootstrapMode = BootstrapMode.LAZY, properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryQueryStartupDiagnosticsTest {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void measuresRealQueryResolutionForSlowRepositories() {
        // Keep order explicit: EventRepository may benefit from JVM/parser warmup.
        profile(UserRepository.class, "searchAdminUsersFiltered");
        profile(EventRepository.class, "findUserEventsListing");
    }

    private void profile(Class<?> repository, String expectedMethod) {
        try (EntityManager em = entityManagerFactory.createEntityManager()) {
            var factory = new TimedRepositoryFactory(em);
            long started = System.nanoTime();
            factory.getRepository(repository);
            System.out.printf("JPA_QUERY_PROFILE repository=%s total_ms=%d methods=%d%n",
                repository.getSimpleName(), elapsedMillis(started), factory.resolved.size());
            assertTrue(factory.resolved.stream().anyMatch(method -> method.getName().equals(expectedMethod)),
                "Expected the suspected query to be resolved by Spring Data");
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static final class TimedRepositoryFactory extends JpaRepositoryFactory {
        private final Set<Method> resolved = new HashSet<>();

        TimedRepositoryFactory(EntityManager em) {
            super(em);
        }

        @Override
        protected Optional<QueryLookupStrategy> getQueryLookupStrategy(
            QueryLookupStrategy.Key key, ValueExpressionDelegate delegate
        ) {
            return super.getQueryLookupStrategy(key, delegate).map(strategy ->
                (method, metadata, projectionFactory, namedQueries) -> {
                    long started = System.nanoTime();
                    try {
                        var query = strategy.resolveQuery(method, metadata, projectionFactory, namedQueries);
                        resolved.add(method);
                        return query;
                    } finally {
                        // Technical identifiers only, no query strings or parameters.
                        System.out.printf("JPA_QUERY_PROFILE repository=%s method=%s elapsed_ms=%d%n",
                            metadata.getRepositoryInterface().getSimpleName(), method.getName(),
                            elapsedMillis(started));
                    }
                });
        }
    }
}
