package org.hayden.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestService;
import org.hayden.ingest.KbDeleteResult;
import org.hayden.ingest.KnowledgeBaseListResponse;
import org.hayden.ingest.KnowledgeBaseStatus;

/**
 * Read-only knowledge-base status surface, served alongside {@code /mcp} and
 * {@code /ingest/*} on the same port.
 *
 * <ul>
 *   <li>{@code GET /kb} — list every KB with stats + document counts, and a
 *       {@code total_documents} roll-up.
 *   <li>{@code GET /kb/{name}} — one KB's status; 404 if it doesn't exist.
 * </ul>
 *
 * No auth (consistent with the rest of the surface); relies on network
 * isolation. Logic lives in core ({@link IngestService}); this class is just
 * the HTTP binding.
 */
@Path("/kb")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "kb", description = "Knowledge-base status: listings and document counts.")
public class KnowledgeBaseResource {

    @Inject
    IngestService ingest;

    @GET
    @Operation(summary = "List knowledge bases",
            description = "Every KB with vector/dim stats, visual-index info, per-KB distinct "
                    + "document counts, and a total_documents roll-up.")
    public KnowledgeBaseListResponse list(
            @Parameter(description = "Backend to query: 'qdrant', 'openwebui', or 'all'. "
                    + "Defaults to the server-configured backend.")
            @QueryParam("backend") String backend) {
        return ingest.listKnowledgeBaseStatuses(backend);
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get one knowledge base",
            description = "Stats + document count for a single KB.")
    @APIResponse(responseCode = "404", description = "No KB with that name")
    public KnowledgeBaseStatus get(
            @Parameter(description = "Knowledge base / collection name")
            @PathParam("name") String name,
            @Parameter(description = "Backend to query; defaults to the server-configured backend")
            @QueryParam("backend") String backend) {
        KnowledgeBaseStatus status = ingest.knowledgeBaseStatus(backend, name);
        if (status == null) {
            throw new NotFoundException("Unknown knowledge base: " + name);
        }
        return status;
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Delete a knowledge base",
            description = "Full teardown: the KB's chunk collection, its visual pages collection, "
                    + "all stored page images, and any queued ingest jobs for it. Idempotent. "
                    + "Requires confirm=true — this destroys the entire index (the source "
                    + "documents on disk are untouched; a re-ingest rebuilds it).")
    @APIResponse(responseCode = "400", description = "Missing confirm=true, or unsupported backend")
    public KbDeleteResult delete(
            @Parameter(description = "Knowledge base / collection name")
            @PathParam("name") String name,
            @Parameter(description = "Must be true — guards against accidental teardown")
            @QueryParam("confirm") boolean confirm,
            @Parameter(description = "Backend; defaults to the server-configured backend")
            @QueryParam("backend") String backend) {
        if (!confirm) {
            throw new IngestException("Refusing to delete knowledge base '" + name
                    + "' without confirm=true. This drops the KB's collections, page images, "
                    + "and queued jobs.");
        }
        return ingest.deleteKnowledgeBase(name, backend);
    }
}
