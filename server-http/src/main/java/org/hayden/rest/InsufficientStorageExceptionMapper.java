package org.hayden.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hayden.ingest.InsufficientStorageException;

import java.util.Map;

/**
 * Maps {@link InsufficientStorageException} (the document store's free-space
 * reserve would be breached) to HTTP 507 Insufficient Storage — distinct from
 * 413, which is the transport-level body cap firing before application code.
 */
@Provider
public class InsufficientStorageExceptionMapper
        implements ExceptionMapper<InsufficientStorageException> {

    @Override
    public Response toResponse(InsufficientStorageException e) {
        return Response.status(507)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
