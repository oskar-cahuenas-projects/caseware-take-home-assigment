package com.caseware.fanout.domain;

/** Lifecycle of a {@link FanoutJob}. */
public enum JobStatus { PLANNING, PLANNED, SUPERSEDED, COMPLETE }
