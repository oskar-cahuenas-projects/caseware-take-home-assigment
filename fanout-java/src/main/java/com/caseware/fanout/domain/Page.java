package com.caseware.fanout.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of results; {@code next} is empty on the last page (G-08). */
public record Page<T>(List<T> items, Optional<Cursor> next) {
    public Page {
        items = List.copyOf(items);
        Objects.requireNonNull(next, "next");
    }
}
