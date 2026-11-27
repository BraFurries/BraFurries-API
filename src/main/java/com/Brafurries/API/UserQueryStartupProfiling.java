package com.Brafurries.API;

import com.Brafurries.API.repository.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.data.jpa.repository.query.JpaQueryMethodFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.querydsl.EntityPathResolver;
import org.springframework.data.querydsl.SimpleEntityPathResolver;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;
import org.springframework.lang.Nullable;

/**
 * Narrow startup diagnostic. Keep Boot's repository registration, bootstrap
 * mode, transaction configuration and fragments; substitute only the factory
 * for UserRepository, before any singleton is created.
 */
public final class UserQueryStartupProfiling
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ENABLED = "brafurries.startup.user-query-profile.enabled";
    private static final String BEAN = "userRepository";
    private static final Logger LOG = LoggerFactory.getLogger(UserQueryStartupProfiling.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (Boolean.parseBoolean(context.getEnvironment().getProperty(ENABLED, "true"))) {
            // A regular factory postprocessor runs after repository registrars.
            context.addBeanFactoryPostProcessor(UserQueryStartupProfiling::install);
        }
    }

    static void install(ConfigurableListableBeanFactory beans) {
        if (!beans.containsBeanDefinition(BEAN)) {
            return;
        }
        BeanDefinition definition = beans.getBeanDefinition(BEAN);
        if (!JpaRepositoryFactoryBean.class.getName().equals(definition.getBeanClassName())) {
            LOG.warn("STARTUP_QUERY_PROFILE skipped reason=unexpected_factory");
            return;
        }
        // Repository registrars may already have populated merged-definition
        // and type caches during postprocessor discovery. Re-register a copy
        // to invalidate those caches instead of mutating the original in place.
        if (!(beans instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry)
                || !(definition instanceof org.springframework.beans.factory.support.AbstractBeanDefinition original)) {
            LOG.warn("STARTUP_QUERY_PROFILE skipped reason=unsupported_registry");
            return;
        }
        if (beans.containsSingleton(BEAN)) {
            LOG.warn("STARTUP_QUERY_PROFILE skipped reason=already_initialized");
            return;
        }
        var replacement = original.cloneBeanDefinition();
        replacement.setBeanClass(ProfiledFactoryBean.class);
        // Explicit target type can still describe the original factory class.
        if (replacement instanceof org.springframework.beans.factory.support.RootBeanDefinition root) {
            root.setTargetType((org.springframework.core.ResolvableType) null);
        }
        registry.registerBeanDefinition(BEAN, replacement);
        try {
            LOG.info("STARTUP_QUERY_PROFILE enabled repository=UserRepository processors={} max_heap_mib={}",
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / (1024 * 1024));
        } catch (RuntimeException ignored) {
            // Diagnostic logging must not change startup behavior.
        }
    }

    public static final class ProfiledFactoryBean<T extends Repository<S, ID>, S, ID>
            extends JpaRepositoryFactoryBean<T, S, ID> {

        private EntityPathResolver pathResolver = SimpleEntityPathResolver.INSTANCE;
        private EscapeCharacter escapeCharacter = EscapeCharacter.DEFAULT;
        private JpaQueryMethodFactory queryMethodFactory;

        public ProfiledFactoryBean(Class<? extends T> repositoryInterface) {
            super(repositoryInterface);
        }

        @Override
        @Autowired
        public void setEntityPathResolver(ObjectProvider<EntityPathResolver> resolver) {
            pathResolver = resolver.getIfAvailable(() -> SimpleEntityPathResolver.INSTANCE);
        }

        @Override
        @Autowired
        public void setQueryMethodFactory(@Nullable JpaQueryMethodFactory factory) {
            queryMethodFactory = factory;
        }

        @Override
        public void setEscapeCharacter(char escapeCharacter) {
            this.escapeCharacter = EscapeCharacter.of(escapeCharacter);
        }

        @Override
        protected RepositoryFactorySupport createRepositoryFactory(EntityManager entityManager) {
            // Match JpaRepositoryFactoryBean in Spring Data JPA 3.5.0.
            // The parent still configures transactions, named queries, fragments,
            // bean factory, metadata, evaluation context and postprocessors.
            var factory = new ProfiledFactory(entityManager);
            factory.setEntityPathResolver(pathResolver);
            factory.setEscapeCharacter(escapeCharacter);
            if (queryMethodFactory != null) {
                factory.setQueryMethodFactory(queryMethodFactory);
            }
            return factory;
        }
    }

    static final class ProfiledFactory extends JpaRepositoryFactory {
        ProfiledFactory(EntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected Optional<QueryLookupStrategy> getQueryLookupStrategy(
                QueryLookupStrategy.Key key, ValueExpressionDelegate delegate) {
            return super.getQueryLookupStrategy(key, delegate).map(strategy ->
                (method, metadata, projectionFactory, namedQueries) -> {
                    if (metadata.getRepositoryInterface() != UserRepository.class) {
                        return strategy.resolveQuery(method, metadata, projectionFactory, namedQueries);
                    }
                    var before = Snapshot.capture();
                    boolean success = false;
                    try {
                        var query = strategy.resolveQuery(method, metadata, projectionFactory, namedQueries);
                        success = true;
                        return query;
                    } finally {
                        // Only method identifiers and numerical diagnostics; never SQL,
                        // parameters, entity data, connection details or exception text.
                        try {
                            var after = Snapshot.capture();
                            LOG.info("STARTUP_QUERY_PROFILE repository=UserRepository method={} elapsed_ms={} thread_cpu_ms={} gc_count_delta={} gc_ms_delta={} success={}",
                                method.getName(), millisDelta(before.wallNanos(), after.wallNanos()),
                                millisDelta(before.cpuNanos(), after.cpuNanos()),
                                delta(before.gcCount(), after.gcCount()),
                                delta(before.gcMillis(), after.gcMillis()), success);
                        } catch (RuntimeException ignored) {
                            // Preserve the delegate's original result or exception.
                        }
                    }
                });
        }
    }

    record Snapshot(long wallNanos, long cpuNanos, long gcCount, long gcMillis) {
        static Snapshot capture() {
            long wall = System.nanoTime();
            long cpu = -1;
            long count = -1;
            long millis = -1;
            try {
                var clock = ManagementFactory.getThreadMXBean();
                if (clock.isCurrentThreadCpuTimeSupported() && clock.isThreadCpuTimeEnabled()) {
                    cpu = clock.getCurrentThreadCpuTime();
                }
            } catch (RuntimeException ignored) {
                // Unsupported counters stay at -1.
            }
            try {
                var collectors = ManagementFactory.getGarbageCollectorMXBeans();
                if (!collectors.isEmpty()) {
                    long gcCount = 0;
                    long gcMillis = 0;
                    for (var collector : collectors) {
                        long c = collector.getCollectionCount();
                        long t = collector.getCollectionTime();
                        gcCount = gcCount < 0 || c < 0 ? -1 : gcCount + c;
                        gcMillis = gcMillis < 0 || t < 0 ? -1 : gcMillis + t;
                    }
                    count = gcCount;
                    millis = gcMillis;
                }
            } catch (RuntimeException ignored) {
                // Optional process-wide counters, not per-thread GC attribution.
            }
            return new Snapshot(wall, cpu, count, millis);
        }
    }

    static long delta(long before, long after) {
        return before < 0 || after < before ? -1 : after - before;
    }

    static long millisDelta(long before, long after) {
        long d = delta(before, after);
        return d < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(d);
    }
}
