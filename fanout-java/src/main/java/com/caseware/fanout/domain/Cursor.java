package com.caseware.fanout.domain;

/** Opaque paging token (G-08). {@link #START} requests the first page, so ports never take {@code null}. */
public record Cursor(String token) {

    public static final Cursor START = new Cursor("start");

    public Cursor {
        Checks.nonBlank(token, "token");
    }
}
