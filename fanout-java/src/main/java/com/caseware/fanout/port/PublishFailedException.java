package com.caseware.fanout.port;

/** The event bus rejected a publish (G-01). The executor does not ack; redelivery republishes (G-22). */
public class PublishFailedException extends RuntimeException {
    public PublishFailedException(String eventId, Throwable cause) {
        super("eventId=" + eventId, cause);
    }
}
