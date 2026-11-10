package com.caseware.fanout.executor;

/**
 * Seam for crash-injection tests (G-11): the executor calls {@link #at} after each durable step.
 * Production uses {@link #NONE}. An implementation simulates process death by throwing an {@link Error},
 * which the executor's {@code catch (Exception)} handlers do not intercept.
 */
@FunctionalInterface
public interface CrashHook {

    /** Points after which a crash leaves distinct durable state (design §7). */
    enum Point { AFTER_CLAIM, AFTER_LOAD, AFTER_RECORD, AFTER_PUBLISH }

    CrashHook NONE = point -> { };

    void at(Point point);
}
