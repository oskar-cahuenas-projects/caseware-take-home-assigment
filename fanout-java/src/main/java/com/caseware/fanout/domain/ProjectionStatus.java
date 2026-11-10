package com.caseware.fanout.domain;

/** Projection row status as seen by the Planner (G-08). Only {@code KNOWN} rows skip the loader. */
public enum ProjectionStatus { KNOWN, UNKNOWN, UNVERIFIED }
