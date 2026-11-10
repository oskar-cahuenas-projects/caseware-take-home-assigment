package com.caseware.fanout.executor;

import com.caseware.fanout.adapter.memory.SemaphoreConcurrencyLimiter;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/** Wires {@code N} executor tasks over shared ports, each with its own limiter and seeded {@link Random} (G-15). Stateless. */
public final class ExecutorFactory {

    private ExecutorFactory() { }

    /** Task {@code i} gets {@code workerId = "worker-i"} and {@code new Random(seed + i)}. */
    public static List<LoadExecutor> create(ExecutorConfig config, ExecutorPorts ports, long seed) {
        return IntStream.range(0, config.tasks())
                .mapToObj(i -> new LoadExecutor("worker-" + i, ports,
                        SemaphoreConcurrencyLimiter.forTask(config), config, new Random(seed + i)))
                .toList();
    }
}
