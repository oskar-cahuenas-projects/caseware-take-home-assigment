package com.caseware.fanout.domain;

import java.util.List;
import java.util.Objects;

/** Argument checks for compact constructors. Stateless; thread-safe. */
public final class Checks {
    private Checks() { }

    public static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, was " + value);
        }
        return value;
    }

    public static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
        return value;
    }

    /** Unmodifiable copy of a list of non-blank IDs. */
    public static List<String> ids(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach(v -> nonBlank(v, name + " element"));
        return List.copyOf(values);
    }
}
