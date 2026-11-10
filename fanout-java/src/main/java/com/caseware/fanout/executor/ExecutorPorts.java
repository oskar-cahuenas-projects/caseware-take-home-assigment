package com.caseware.fanout.executor;

import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.port.EngagementLoader;
import com.caseware.fanout.port.FanoutJobStore;
import com.caseware.fanout.port.LoadLedger;
import com.caseware.fanout.port.ObservationPublisher;
import com.caseware.fanout.port.Scheduler;
import com.caseware.fanout.port.WorkQueue;
import java.time.Clock;
import java.util.Objects;

/** The ports every executor task in a cell shares (G-15). The limiter is per task and not included. Immutable. */
public record ExecutorPorts(WorkQueue<LoadRequest> queue, LoadLedger ledger, FanoutJobStore jobs,
        ObservationPublisher publisher, EngagementLoader loader, Scheduler scheduler, Clock clock) {

    public ExecutorPorts {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(clock, "clock");
    }
}
