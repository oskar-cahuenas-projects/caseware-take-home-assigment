package com.caseware.fanout.executor;

import com.caseware.fanout.domain.LoadRequest;
import com.caseware.fanout.port.Delivery;
import com.caseware.fanout.port.Scheduler;
import com.caseware.fanout.port.WorkQueue;

/**
 * Visibility heartbeat (§C): while a load runs, extends the message's visibility by
 * {@code heartbeatExtension} every {@code heartbeatInterval}. {@link #close()} cancels it on every exit
 * path (EC-27). Thread-safe.
 */
final class Heartbeat implements AutoCloseable {

    private final Scheduler.Cancellable task;

    Heartbeat(Scheduler scheduler, WorkQueue<LoadRequest> queue, Delivery<LoadRequest> delivery,
              ExecutorConfig config) {
        task = scheduler.scheduleAtFixedRate(() -> queue.extendVisibility(delivery, config.heartbeatExtension()),
                config.heartbeatInterval(), config.heartbeatInterval());
    }

    @Override
    public void close() {
        task.cancel();
    }
}
