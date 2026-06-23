package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The per-file result of a directory ingest.
 *
 * @param path       absolute source path
 * @param filename   file name
 * @param docId      deterministic document id (UUIDv5 of kb + path)
 * @param status     {@code "completed"} (ingested synchronously),
 *                   {@code "queued"} (submitted to the async queue), or
 *                   {@code "error"} (this file failed; see {@code message})
 * @param jobId      async job id when {@code status == "queued"}, else null —
 *                   poll {@code GET /ingest/status/{jobId}} or the
 *                   {@code get_ingest_status} MCP tool
 * @param chunkCount chunks ingested (null for queued/error)
 * @param pageCount  pages visual-indexed (null for queued/error)
 * @param message    human-readable status / error detail
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DirectoryFileOutcome(
        String path,
        String filename,
        String docId,
        String status,
        String jobId,
        Integer chunkCount,
        Integer pageCount,
        String message) {
}
