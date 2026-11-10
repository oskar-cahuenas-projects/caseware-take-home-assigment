package com.caseware.fanout.testkit;

import com.caseware.fanout.domain.EngagementLoadFailed;
import com.caseware.fanout.domain.EngagementStateObserved;
import com.caseware.fanout.domain.EngagementTemplateState;
import com.caseware.fanout.domain.FailureReason;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The consumer side of I6 as the executor relies on it (§D rows 7, 8, 10; G-06): {@code eventId} dedupe,
 * a sequence guard, and, for {@code seqKnown=false}, filling {@code UNKNOWN} fields only. No head
 * computation (out of scope). Thread-safe (all methods synchronized).
 */
public final class FakeTrackingServiceConsumer {

    /** What one event did to the projection. */
    public enum Effect { APPLIED, DUPLICATE, STALE, FILLED_UNKNOWN }

    /** A projection row; an empty field is {@code UNKNOWN}. */
    public record Row(Optional<String> templateId, Optional<String> branchId, Optional<String> curVersionId,
                      OptionalLong seq, Optional<FailureReason> loadFailed) {

        public static Row unknown() {
            return new Row(Optional.empty(), Optional.empty(), Optional.empty(), OptionalLong.empty(), Optional.empty());
        }

        /** A row written by another source (for example a user decision) at {@code seq}. */
        public static Row known(String templateId, String branchId, String curVersionId, long seq) {
            return new Row(Optional.of(templateId), Optional.of(branchId), Optional.of(curVersionId),
                    OptionalLong.of(seq), Optional.empty());
        }
    }

    private final Map<String, Row> rows = new HashMap<>();
    private final Set<String> seenEventIds = new HashSet<>();

    public synchronized void seed(String engagementId, Row row) {
        rows.put(engagementId, row);
    }

    public synchronized Row row(String engagementId) {
        return rows.getOrDefault(engagementId, Row.unknown());
    }

    public synchronized Effect apply(EngagementStateObserved e) {
        if (!seenEventIds.add(e.eventId())) {
            return Effect.DUPLICATE;
        }
        EngagementTemplateState s = e.state();
        Row current = row(s.engagementId());
        if (!e.seqKnown()) {
            rows.put(s.engagementId(), new Row(
                    current.templateId().or(() -> Optional.of(s.templateId())),
                    current.branchId().or(() -> Optional.of(s.branchId())),
                    current.curVersionId().or(() -> Optional.of(s.curVersionId())),
                    current.seq(), current.loadFailed()));
            return Effect.FILLED_UNKNOWN;
        }
        long seq = s.engagementSeq().getAsLong();
        if (current.seq().isPresent() && seq <= current.seq().getAsLong()) {
            return Effect.STALE;
        }
        rows.put(s.engagementId(), new Row(Optional.of(s.templateId()), Optional.of(s.branchId()),
                Optional.of(s.curVersionId()), OptionalLong.of(seq), Optional.empty()));
        return Effect.APPLIED;
    }

    public synchronized Effect apply(EngagementLoadFailed e) {
        if (!seenEventIds.add(e.eventId())) {
            return Effect.DUPLICATE;
        }
        Row current = row(e.engagementId());
        rows.put(e.engagementId(), new Row(current.templateId(), current.branchId(), current.curVersionId(),
                current.seq(), Optional.of(e.reason())));
        return Effect.APPLIED;
    }
}
