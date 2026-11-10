package com.caseware.fanout.domain;

/** Load-request lanes (I4), polled 6:3:1. */
public enum Lane { PUBLISH, BACKFILL, RECONCILE }
