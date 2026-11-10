/**
 * Ports I2–I7 and infrastructure ports (queue, ledger, limiter, job store, publisher, loader, scheduler).
 * Every port implementation must be thread-safe; the value types here ({@code Claim}, {@code Delivery},
 * exceptions) are immutable.
 */
package com.caseware.fanout.port;
