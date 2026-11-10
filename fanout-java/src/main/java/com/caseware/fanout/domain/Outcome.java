package com.caseware.fanout.domain;

/** Per-item job outcome (G-07). {@code COALESCED} counts as done. */
public enum Outcome { DONE, COALESCED, DLQ }
