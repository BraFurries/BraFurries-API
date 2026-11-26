package com.Brafurries.API;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class StartupDiagnosticsTest {

    @Test
    void ranksBeansAndPhasesSeparatelyWithStableLimit() {
        var fast = new StartupDiagnostics.Entry(3, 1L, "spring.beans.instantiate", "fast", Duration.ofMillis(10));
        var slow = new StartupDiagnostics.Entry(2, 1L, "spring.beans.instantiate", "slow", Duration.ofSeconds(30));
        var same = new StartupDiagnostics.Entry(4, 1L, "spring.beans.instantiate", "same", Duration.ofSeconds(30));
        var phase = new StartupDiagnostics.Entry(1, null, "spring.context.refresh", "-", Duration.ofSeconds(40));
        var entries = List.of(fast, same, phase, slow);

        assertEquals(List.of(slow, same), StartupDiagnostics.slowest(entries, true, 2));
        assertEquals(List.of(phase), StartupDiagnostics.slowest(entries, false, 10));
        assertEquals(List.of(), StartupDiagnostics.slowest(entries, true, 0));
    }

    @Test
    void recordsActualSpringStepsWithoutLoggingArbitraryTags(CapturedOutput output) {
        var startup = StartupDiagnostics.create();
        var parent = startup.start("spring.context.refresh");
        var child = startup.start("spring.beans.instantiate");
        child.tag("beanName", "exampleRepository");
        child.tag("unrelated", "private-fixture-value");
        child.end();
        parent.end();

        var events = startup.getBufferedTimeline().getEvents();
        assertEquals(2, events.size());
        var bean = events.stream().map(StartupDiagnostics::entry)
            .filter(entry -> "exampleRepository".equals(entry.bean())).findFirst().orElseThrow();
        assertEquals(child.getId(), bean.id());
        assertEquals(Long.valueOf(parent.getId()), bean.parentId());
        assertFalse(bean.duration().isNegative());

        StartupDiagnostics.report(startup);

        assertTrue(output.getOut().contains("STARTUP_PROFILE events=2"));
        assertTrue(output.getOut().contains("bean=exampleRepository"));
        assertFalse(output.getAll().contains("private-fixture-value"));
        assertTrue(startup.getBufferedTimeline().getEvents().isEmpty());

        startup.start("spring.beans.instantiate").tag("beanName", "lateBean").end();
        assertTrue(startup.getBufferedTimeline().getEvents().isEmpty());
    }

    @Test
    void rejectsUnexpectedLabels() {
        assertEquals("exampleRepository", StartupDiagnostics.safeLabel("exampleRepository"));
        assertEquals("[redacted]", StartupDiagnostics.safeLabel("password=value"));
        assertEquals("[redacted]", StartupDiagnostics.safeLabel("line\nbreak"));
        assertEquals("[redacted]", StartupDiagnostics.safeLabel(null));
        assertEquals("[redacted]", StartupDiagnostics.safeLabel("a".repeat(201)));
    }
}
