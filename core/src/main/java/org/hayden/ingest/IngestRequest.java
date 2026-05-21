package org.hayden.ingest;

import java.util.Map;

public record IngestRequest(
        SourceType sourceType,
        String sourceValue,
        String filename,
        String kbName,
        String kbDescription,
        long pollTimeoutSeconds,
        String backend,
        Map<String, Object> metadata,
        Boolean enableVisualIndex) {

    /** Back-compat: no per-call visual-index override (use env default). */
    public IngestRequest(SourceType sourceType, String sourceValue, String filename,
                         String kbName, String kbDescription, long pollTimeoutSeconds,
                         String backend, Map<String, Object> metadata) {
        this(sourceType, sourceValue, filename, kbName, kbDescription,
                pollTimeoutSeconds, backend, metadata, null);
    }

    /** Back-compat: no backend or metadata override. */
    public IngestRequest(SourceType sourceType, String sourceValue, String filename,
                         String kbName, String kbDescription, long pollTimeoutSeconds) {
        this(sourceType, sourceValue, filename, kbName, kbDescription,
                pollTimeoutSeconds, null, null, null);
    }

    public enum SourceType {
        URL, PATH, INLINE;

        public static SourceType parse(String raw) {
            if (raw == null) {
                throw new IngestException("source_type is required");
            }
            return switch (raw.trim().toLowerCase()) {
                case "url" -> URL;
                case "path" -> PATH;
                case "inline" -> INLINE;
                default -> throw new IngestException(
                        "Unknown source_type='" + raw + "' (expected url|path|inline)");
            };
        }
    }
}
