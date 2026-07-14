package org.hayden.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hayden.ingest.DeleteResult;
import org.hayden.ingest.DirectoryIngestRequest;
import org.hayden.ingest.DirectoryIngestResponse;
import org.hayden.ingest.DirectoryIngestService;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;

/**
 * Plain REST surface for directory-based ingestion, served alongside the MCP
 * {@code /mcp} endpoint on the same HTTP port. Lets non-MCP callers (cron jobs,
 * curl, an admin UI) point the server at a directory on disk and have every
 * matching file ingested.
 *
 * <ul>
 *   <li>{@code POST   /ingest/directory} — scan a directory and ingest its files.
 *   <li>{@code GET    /ingest/status/{jobId}} — poll a queued file's job.
 *   <li>{@code DELETE /ingest/document} — remove a document by doc_id or source_path.
 * </ul>
 *
 * No auth (consistent with the MCP endpoint); relies on network isolation.
 */
@Path("/ingest")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "ingest", description = "Directory-based ingestion, job status, and document deletion.")
public class IngestResource {

    @Inject
    DirectoryIngestService directoryIngest;

    @Inject
    IngestQueue queue;

    @POST
    @Path("/directory")
    @Operation(summary = "Ingest a directory",
            description = "Scan a directory on disk and ingest every matching file. "
                    + "Idempotent across re-scans via deterministic per-file doc IDs.")
    public DirectoryIngestResponse ingestDirectory(DirectoryIngestRequest req) {
        return directoryIngest.ingestDirectory(req);
    }

    @GET
    @Path("/status/{jobId}")
    @Operation(summary = "Poll a queued ingest job",
            description = "Return the status and result of a queued file's ingest job.")
    @APIResponse(responseCode = "404", description = "No job exists with that job_id")
    public JobStatusView status(
            @Parameter(description = "The job_id returned when a file was queued for async ingest")
            @PathParam("jobId") String jobId) {
        IngestJob job = queue.getJob(jobId)
                .orElseThrow(() -> new NotFoundException("Unknown job_id: " + jobId));
        return JobStatusView.of(job);
    }

    /**
     * Delete a document from a KB. Identify it by {@code doc_id}, or by
     * {@code source_path} (the absolute path it was ingested from — resolved to
     * the same deterministic id the directory scan assigned). Idempotent.
     */
    @DELETE
    @Path("/document")
    @Operation(summary = "Delete a document",
            description = "Remove a document from a KB by doc_id, or by source_path (the absolute "
                    + "path it was ingested from, resolved to the same deterministic id the "
                    + "directory scan assigned). Idempotent.")
    public DeleteResult deleteDocument(
            @Parameter(description = "Target knowledge base / collection name")
            @QueryParam("kb_name") String kbName,
            @Parameter(description = "Document id to delete (mutually exclusive with source_path)")
            @QueryParam("doc_id") String docId,
            @Parameter(description = "Absolute source path the document was ingested from")
            @QueryParam("source_path") String sourcePath,
            @Parameter(description = "Backend override; defaults to the configured INGEST_BACKEND")
            @QueryParam("backend") String backend) {
        return directoryIngest.deleteDocument(kbName, docId, sourcePath, backend);
    }
}
