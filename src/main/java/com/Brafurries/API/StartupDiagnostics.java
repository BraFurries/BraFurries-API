package com.Brafurries.API;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.core.metrics.StartupStep;

/**
 * Bounded, internal startup diagnostics. Times are inclusive (a parent may
 * include the cost of its children); they must not be summed.
 */
final class StartupDiagnostics {
    static final int CAPACITY = 8192;
    static final int LIMIT = 20;
    private static final Logger LOG = LoggerFactory.getLogger(StartupDiagnostics.class);
    private static final String BEAN_STEP = "spring.beans.instantiate";

    private StartupDiagnostics() {}

    static BufferingApplicationStartup create() {
        return new BufferingApplicationStartup(CAPACITY);
    }

    static void report(BufferingApplicationStartup startup) {
        try {
            // No request-time measurements or indefinitely retained startup tags.
            startup.addFilter(step -> false);
            var timeline = startup.drainBufferedTimeline();
            var entries = timeline.getEvents().stream().map(StartupDiagnostics::entry).toList();
            LOG.info("STARTUP_PROFILE events={} capacity={} possibly_truncated={} timings=inclusive",
                entries.size(), CAPACITY, entries.size() >= CAPACITY);
            logRanked("bean", slowest(entries, true, LIMIT));
            logRanked("phase", slowest(entries, false, 10));
        } catch (RuntimeException ex) {
            // Optional diagnostics must never turn a successful startup into a failure.
            // Do not print exception messages, which might include arbitrary tag values.
            LOG.warn("STARTUP_PROFILE unavailable error_type={}", ex.getClass().getSimpleName());
        }
    }

    record Entry(long id, Long parentId, String step, String bean, Duration duration) {}

    static Entry entry(StartupTimeline.TimelineEvent event) {
        StartupStep step = event.getStartupStep();
        String bean = "-";
        if (BEAN_STEP.equals(step.getName())) {
            for (StartupStep.Tag tag : step.getTags()) {
                if ("beanName".equals(tag.getKey())) {
                    bean = safeLabel(tag.getValue());
                    break;
                }
            }
        }
        return new Entry(step.getId(), step.getParentId(), safeLabel(step.getName()), bean,
            event.getDuration());
    }

    static List<Entry> slowest(List<Entry> entries, boolean beans, int limit) {
        return entries.stream()
            .filter(entry -> BEAN_STEP.equals(entry.step()) == beans)
            .sorted(Comparator.comparing(Entry::duration).reversed().thenComparingLong(Entry::id))
            .limit(limit)
            .toList();
    }

    private static void logRanked(String category, List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            LOG.info("STARTUP_PROFILE category={} rank={} elapsed_ms={} id={} parent_id={} step={} bean={}",
                category, i + 1, entry.duration().toMillis(), entry.id(), entry.parentId(), entry.step(), entry.bean());
        }
    }

    // Only technical identifiers from explicitly allowlisted tags; never log
    // arbitrary tag maps, SQL, configuration, credentials or exception text.
    static String safeLabel(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$#-]{1,200}") ? value : "[redacted]";
    }
}
