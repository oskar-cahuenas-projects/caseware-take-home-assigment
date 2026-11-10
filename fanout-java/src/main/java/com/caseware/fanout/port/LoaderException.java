package com.caseware.fanout.port;

import com.caseware.fanout.domain.Checks;
import java.time.Duration;
import java.util.Objects;

/**
 * Failure classification for {@link EngagementLoader} (I5). Sealed so the executor's {@code switch}
 * is checked for exhaustiveness. Messages carry the engagement ID only (data residency).
 */
public abstract sealed class LoaderException extends Exception {

    private final String engagementId;

    private LoaderException(String engagementId, Throwable cause) {
        super("engagementId=" + Checks.nonBlank(engagementId, "engagementId"), cause);
        this.engagementId = engagementId;
    }

    public String engagementId() {
        return engagementId;
    }

    /** 429 or saturation. Retried with backoff of at least {@code retryAfter}; never dead-lettered. */
    public static final class Throttled extends LoaderException {
        private final Duration retryAfter;

        /** @param retryAfter server hint; {@link Duration#ZERO} when none was given */
        public Throttled(String engagementId, Duration retryAfter) {
            super(engagementId, null);
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** Timeout or retriable server error. Retried with bounded attempts (G-16). */
    public static final class Transient extends LoaderException {
        public Transient(String engagementId, Throwable cause) {
            super(engagementId, cause);
        }
    }

    /** Engagement missing or archived. Dead-lettered immediately. */
    public static final class NotFound extends LoaderException {
        public NotFound(String engagementId) {
            super(engagementId, null);
        }
    }

    /** Non-retriable failure. Dead-lettered immediately. */
    public static final class Permanent extends LoaderException {
        public Permanent(String engagementId) {
            super(engagementId, null);
        }
    }
}
