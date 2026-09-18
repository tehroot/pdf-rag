package org.hayden.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hayden.ingest.SourceConflictException;

import java.util.Map;

/**
 * Maps {@link SourceConflictException} (an on_conflict=replace refused because
 * a queued job still reads the target file and no byte snapshot exists) to a
 * 409 with a JSON {@code {"error": ...}} body naming the job id. More specific
 * than {@link IngestExceptionMapper}, so JAX-RS picks this one for the subclass.
 */
@Provider
public class SourceConflictExceptionMapper implements ExceptionMapper<SourceConflictException> {

    @Override
    public Response toResponse(SourceConflictException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}
