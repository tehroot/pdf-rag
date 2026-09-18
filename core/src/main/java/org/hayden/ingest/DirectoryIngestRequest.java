package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /ingest/directory}: scan a directory on disk
 * and ingest every matching file into a knowledge base. JSON is snake_case
 * (e.g. {@code kb_name}, {@code enable_visual_index}) to match the MCP tool
 * surface.
 *
 * @param directory          absolute path of the directory to scan (required)
 * @param kbName             target knowledge base (required)
 * @param kbDescription      optional KB description (used only on create)
 * @param recursive          descend into subdirectories; default true
 * @param extensions         file extensions to include (with or without a
 *                           leading dot); null/empty → a default document set
 * @param backend            backend override; null → configured default
 * @param enableVisualIndex  visual-index override; null → env default
 * @param metadata           extra payload metadata applied to every file
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DirectoryIngestRequest(
        String directory,
        String kbName,
        String kbDescription,
        Boolean recursive,
        List<String> extensions,
        String backend,
        Boolean enableVisualIndex,
        Map<String, Object> metadata) {
}
