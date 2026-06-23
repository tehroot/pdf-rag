package org.hayden.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hayden.ingest.IngestException;

import java.util.Map;

/**
 * Maps {@link IngestException} (bad input — missing kb_name, non-absolute or
 * non-existent directory, etc.) to a 400 with a JSON {@code {"error": ...}}
 * body. Per-file ingest failures never reach here — the directory service
 * captures those as {@code "error"} outcomes in the 200 response.
 */
@Provider
public class IngestExceptionMapper implements ExceptionMapper<IngestException> {

    @Override
    public Response toResponse(IngestException e) {
        String msg = e.getMessage() == null ? "ingest error" : e.getMessage();
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg))
                .build();
    }
}
