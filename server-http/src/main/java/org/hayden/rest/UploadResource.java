package org.hayden.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.UploadIngestRequest;
import org.hayden.ingest.UploadIngestResponse;
import org.hayden.ingest.UploadIngestService;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multipart upload ingest: the push counterpart to {@code POST
 * /ingest/directory}. Files land in the durable document store
 * ({@code INGEST_UPLOAD_ROOT}, {@code /documents} in the container) and are
 * then ingested with the same deterministic doc ids a directory scan of that
 * tree would assign. A separate resource class because {@link IngestResource}
 * is {@code @Consumes(APPLICATION_JSON)} at class level.
 *
 * <p>Logic lives in core ({@link UploadIngestService},
 * {@code UploadedDocumentStore}); this class only binds the multipart form.
 * No auth (consistent with the rest of the surface); relies on network
 * isolation — note this is the first endpoint that WRITES caller bytes to
 * permanent server storage.
 */
@Path("/ingest/upload")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "ingest",
        description = "Directory-based ingestion, job status, and document deletion.")
public class UploadResource {

    @Inject
    UploadIngestService uploads;

    @Inject
    ObjectMapper objectMapper;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload and ingest documents",
            description = "Store 1..N multipart files in the durable document store "
                    + "(<root>/<kb_name>/<subdir>/) and ingest each; .zip parts are "
                    + "expanded by default. Doc ids are deterministic per stored path, "
                    + "so re-uploads and later directory scans of the store are "
                    + "idempotent. ingest=false stores without indexing. NOTE: the "
                    + "transport body cap (quarkus.http.limits.max-body-size / "
                    + "UPLOAD_MAX_BODY_SIZE) rejects oversized requests with 413 before "
                    + "this endpoint runs and must equal ingest.upload.max_request_bytes; "
                    + "507 means the store's free-space reserve would be breached.")
    @APIResponse(responseCode = "400", description = "Invalid form fields")
    @APIResponse(responseCode = "409",
            description = "on_conflict=replace refused: a queued job still reads the target file")
    @APIResponse(responseCode = "413", description = "Request exceeds the body/request byte cap")
    @APIResponse(responseCode = "507", description = "Store free-space reserve would be breached")
    public UploadIngestResponse upload(
            @RestForm("files") List<FileUpload> files,
            @RestForm("kb_name") String kbName,
            @RestForm("kb_description") String kbDescription,
            @RestForm("subdir") String subdir,
            @RestForm("backend") String backend,
            @RestForm("enable_visual_index") String enableVisualIndex,
            @RestForm("metadata") String metadata,
            @RestForm("on_conflict") String onConflict,
            @RestForm("ingest") String ingest,
            @RestForm("expand_archives") String expandArchives) {
        UploadIngestRequest req = new UploadIngestRequest(
                kbName, blankToNull(kbDescription), blankToNull(subdir),
                blankToNull(backend), parseBool("enable_visual_index", enableVisualIndex),
                parseMetadata(metadata), blankToNull(onConflict),
                parseBool("ingest", ingest), parseBool("expand_archives", expandArchives));
        List<UploadIngestService.IncomingFile> incoming = new ArrayList<>();
        if (files != null) {
            for (FileUpload f : files) {
                incoming.add(new UploadIngestService.IncomingFile(
                        f.fileName(), f.uploadedFile()));
            }
        }
        return uploads.ingestUploads(req, incoming);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Strict tri-state boolean: absent → null (use the configured default). */
    private static Boolean parseBool(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IngestException(
                    field + " must be true or false; got: " + raw);
        };
    }

    private Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IngestException("metadata must be a JSON object: " + e.getMessage());
        }
    }
}
