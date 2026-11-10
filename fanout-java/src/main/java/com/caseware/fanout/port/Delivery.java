package com.caseware.fanout.port;

import com.caseware.fanout.domain.Checks;
import java.util.Objects;

/** One receive of a queue message. The {@code receipt} is new on every receive; a stale receipt is a no-op. */
public record Delivery<T>(T body, String receipt, int receiveCount) {
    public Delivery {
        Objects.requireNonNull(body, "body");
        Checks.nonBlank(receipt, "receipt");
        Checks.positive(receiveCount, "receiveCount");
    }
}
