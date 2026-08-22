package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Response for {@code POST /ingest/upload}: a summary plus the per-file
 * outcomes (one per stored document — an expanded ZIP contributes one per
 * entry). Mirrors {@link DirectoryIngestResponse}, with the store {@code root}
 * in place of the scanned {@code directory}, so a client parses one outcome
 * format for both bulk paths.
 * {@code completed + queued + stored + failed == filesFound}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UploadIngestResponse(
        String root,
        String kbName,
        int filesFound,
        int completed,
        int queued,
        int stored,
        int failed,
        List<DirectoryFileOutcome> files) {
}
