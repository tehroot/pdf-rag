package org.hayden.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hayden.ingest.PayloadTooLargeException;

import java.util.Map;

/**
 * Maps {@link PayloadTooLargeException} (the application-level request-bytes
 * cap, ingest.upload.max_request_bytes) to 413 — the same code Quarkus's
 * max-body-size cap answers with, so a client sees one code for "too big"
 * regardless of which layer caught it.
 */
@Provider
public class PayloadTooLargeExceptionMapper
        implements ExceptionMapper<PayloadTooLargeException> {

    @Override
    public Response toResponse(PayloadTooLargeException e) {
        return Response.status(413)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
