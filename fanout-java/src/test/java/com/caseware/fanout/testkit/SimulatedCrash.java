package com.caseware.fanout.testkit;

import com.caseware.fanout.executor.CrashHook;

/** Models process death at a {@link CrashHook.Point}. An {@link Error}, so executor handlers do not catch it (G-11). */
public final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    public SimulatedCrash(CrashHook.Point point) {
        super("simulated crash at " + point);
    }
}
