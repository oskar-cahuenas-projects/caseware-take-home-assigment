package com.caseware.fanout.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.JobStatus;
import com.caseware.fanout.planner.Planner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Keeps the README demo working: it runs to completion and its numbers respect the invariants. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DemoTest {

    @Test
    @DisplayName("Demo: both jobs complete; capacity, firm cap, coalescing and DLQ counts hold")
    void demoRuns() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Demo.Summary s = Demo.run(new PrintStream(buf, true, StandardCharsets.UTF_8));

        int unique = Demo.BIG_FIRM_UNKNOWN + Demo.SMALL_FIRMS * Demo.SMALL_FIRM_UNKNOWN;
        int notFound = Demo.NOT_FOUND.size();
        assertThat(s.v7Status()).isEqualTo(JobStatus.COMPLETE);
        assertThat(s.v8Status()).isEqualTo(JobStatus.COMPLETE);
        assertThat(s.duplicate()).isEqualTo(Planner.Result.DUPLICATE);
        assertThat(s.requestsV7()).isEqualTo(unique);
        assertThat(s.peakInFlight()).isLessThanOrEqualTo(s.capacity());
        assertThat(s.peakBigFirm()).isLessThanOrEqualTo(s.firmCapAllTasks());
        assertThat(s.coalesced()).isEqualTo(unique - notFound);
        assertThat(s.callsForV8()).isEqualTo(notFound);
        assertThat(s.loaderCalls()).isEqualTo(unique + Demo.THROTTLED_ONCE.size() + notFound);
        assertThat(s.deadLettered()).isEqualTo(2 * notFound);
        assertThat(s.headBatches()).isEqualTo(2 * Demo.KNOWN / 100);
        assertThat(s.observations()).isEqualTo(unique - notFound);
        assertThat(s.consumerApplied()).isEqualTo(unique - notFound);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Fan-out worker demo", "Jobs", "Tracking Service");
    }
}
