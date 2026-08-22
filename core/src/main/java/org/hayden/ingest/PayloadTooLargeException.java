package org.hayden.ingest;

/**
 * The request's materialized bytes exceed {@code ingest.upload.max_request_bytes}
 * — the application-level twin of {@code quarkus.http.limits.max-body-size}.
 * The two are meant to be configured equal (Quarkus rejects first, while the
 * body is still streaming); this only fires when they drift. Mapped to HTTP
 * 413 so the caller sees the same code either way.
 */
public class PayloadTooLargeException extends IngestException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
