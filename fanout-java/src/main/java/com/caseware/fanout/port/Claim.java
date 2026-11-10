package com.caseware.fanout.port;

import static com.caseware.fanout.domain.Checks.nonBlank;

import com.caseware.fanout.domain.Checks;
import com.caseware.fanout.domain.EngagementTemplateState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Result of {@link LoadLedger#claim}, decided atomically per G-19 (payloads per G-04). */
public sealed interface Claim {

    /** This worker owns the entry until {@code leaseUntil} and must load. */
    record Claimed(Instant leaseUntil) implements Claim {
        public Claimed {
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    /** A fresh enough observation exists; record {@code COALESCED} and ack without loading. */
    record AlreadyFresh(OptionalLong observedSeq, Instant observedAt) implements Claim {
        public AlreadyFresh {
            Objects.requireNonNull(observedSeq, "observedSeq");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /** Another worker holds a live lease; requeue until it ends (G-05). */
    record InFlightElsewhere(String ownerWorkerId, Instant leaseUntil) implements Claim {
        public InFlightElsewhere {
            nonBlank(ownerWorkerId, "ownerWorkerId");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    /** An observation was recorded but not published (outbox); republish with the same {@code eventId}. */
    record PendingPublish(EngagementTemplateState state, String eventId, List<String> jobIds) implements Claim {
        public PendingPublish {
            Objects.requireNonNull(state, "state");
            nonBlank(eventId, "eventId");
            jobIds = Checks.ids(jobIds, "jobIds");
        }
    }
}
