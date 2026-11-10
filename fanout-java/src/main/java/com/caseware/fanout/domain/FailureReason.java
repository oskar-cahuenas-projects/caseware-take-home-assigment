package com.caseware.fanout.domain;

/** Why a load was dead-lettered (G-07, G-16). */
public enum FailureReason { NOT_FOUND, PERMANENT, TRANSIENT_EXHAUSTED }
