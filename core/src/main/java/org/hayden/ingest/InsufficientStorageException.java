package org.hayden.ingest;

/**
 * Storing the request's bytes would drop the document store's free space below
 * {@code ingest.upload.min_free_bytes}. Mapped to HTTP 507 on the REST surface
 * — distinct from 413, which is the transport-level body cap
 * ({@code quarkus.http.limits.max-body-size}) rejecting the request before any
 * application code runs.
 */
public class InsufficientStorageException extends IngestException {

    public InsufficientStorageException(String message) {
        super(message);
    }
}
