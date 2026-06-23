package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Response for {@code POST /ingest/directory}: a summary plus the per-file
 * outcomes. {@code completed + queued + failed == filesFound}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DirectoryIngestResponse(
        String directory,
        String kbName,
        int filesFound,
        int completed,
        int queued,
        int failed,
        List<DirectoryFileOutcome> files) {
}
