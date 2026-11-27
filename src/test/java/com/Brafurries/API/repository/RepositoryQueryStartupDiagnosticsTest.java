package com.Brafurries.API.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventListingRepositoryImpl;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.management.ManagementFactory;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
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
    void profilesEventRepository() {
        profile(EventRepository.class, "findWithLocaleById");
    }

    @Test
    void profilesEventRepositoryAfterControl() {
        profile(ControlRepository.class, "findControlPage");
        profile(EventRepository.class, "findWithLocaleById");
    }

    // A minimal paged JPQL query, using the same factory, entity mapping,
    // explicit count query and parser machinery as the production methods.
    // Never discovered as a Spring Data bean and never executed.
    @NoRepositoryBean
    public interface ControlRepository extends Repository<User, Integer> {
        @Query(value = "SELECT u FROM User u",
            countQuery = "SELECT COUNT(u) FROM User u")
        Page<User> findControlPage(Pageable pageable);
    }

    private void profile(Class<?> repository, String expectedMethod) {
        try (EntityManager em = entityManagerFactory.createEntityManager()) {
            var factory = new TimedRepositoryFactory(em);
            long started = System.nanoTime();
            long cpuStarted = threadCpuNanos();
            if (repository == EventRepository.class) {
                factory.getRepository(repository, RepositoryFragments.just(new EventListingRepositoryImpl(em)));
            } else {
                factory.getRepository(repository);
            }
            System.out.printf("JPA_QUERY_PROFILE repository=%s total_ms=%d methods=%d thread_cpu_ms=%d%n",
                repository.getSimpleName(), elapsedMillis(started), factory.resolved.size(),
                elapsedCpuMillis(cpuStarted));
            assertTrue(factory.resolved.stream().anyMatch(method -> method.getName().equals(expectedMethod)),
                "Expected the declared query to be resolved by Spring Data");
            if (repository == EventRepository.class) {
                assertFalse(factory.resolved.stream().anyMatch(method -> method.getName().equals("findUserEventsListing")),
                    "Criteria-based search must not resolve a declared JPQL query at startup");
            }
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    // CPU time measures only the calling thread, not JVM compiler/GC workers.
    // A missing/disabled CPU clock is represented by -1.
    private static long threadCpuNanos() {
        var clock = ManagementFactory.getThreadMXBean();
        return clock.isCurrentThreadCpuTimeSupported() && clock.isThreadCpuTimeEnabled()
            ? clock.getCurrentThreadCpuTime() : -1;
    }

    private static long elapsedCpuMillis(long started) {
        long ended = threadCpuNanos();
        return started < 0 || ended < started ? -1 : TimeUnit.NANOSECONDS.toMillis(ended - started);
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
                    long cpuStarted = threadCpuNanos();
                    try {
                        var query = strategy.resolveQuery(method, metadata, projectionFactory, namedQueries);
                        resolved.add(method);
                        return query;
                    } finally {
                        // Technical identifiers only, no query strings or parameters.
                        System.out.printf("JPA_QUERY_PROFILE repository=%s method=%s elapsed_ms=%d thread_cpu_ms=%d%n",
                            metadata.getRepositoryInterface().getSimpleName(), method.getName(),
                            elapsedMillis(started), elapsedCpuMillis(cpuStarted));
                    }
                });
        }
    }
}
