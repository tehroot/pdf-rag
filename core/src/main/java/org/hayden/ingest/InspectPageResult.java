package org.hayden.ingest;

/**
 * Result of the {@code inspect_page} MCP tool. Carries the rendered page as
 * base64-encoded PNG plus enough provenance so an agent can correlate the
 * image with the search hit that referenced it.
 */
public record InspectPageResult(
        String kbName,
        String docId,
        int pageNumber,
        int width,
        int height,
        String base64Png) {
}
