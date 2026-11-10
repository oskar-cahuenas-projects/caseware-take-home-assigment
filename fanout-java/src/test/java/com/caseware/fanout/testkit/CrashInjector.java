package com.caseware.fanout.testkit;

import com.caseware.fanout.executor.CrashHook;
import java.util.concurrent.atomic.AtomicReference;

/** {@link CrashHook} that throws {@link SimulatedCrash} once at the armed point. Thread-safe. */
public final class CrashInjector implements CrashHook {

    private final AtomicReference<Point> armed = new AtomicReference<>();

    /** The next {@link #at} call with {@code point} crashes; later calls do not. */
    public void arm(Point point) {
        armed.set(point);
    }

    @Override
    public void at(Point point) {
        if (armed.compareAndSet(point, null)) {
            throw new SimulatedCrash(point);
        }
    }
}
