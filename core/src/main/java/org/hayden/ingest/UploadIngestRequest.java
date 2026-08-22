package org.hayden.ingest;

import java.util.Map;

/**
 * The non-file form fields of {@code POST /ingest/upload}, parsed by the HTTP
 * binding into a typed record before they reach {@link UploadIngestService}.
 *
 * @param kbName            target knowledge base; also the top-level store
 *                          directory (single path segment, validated)
 * @param kbDescription     optional KB description (used only on create)
 * @param subdir            relative directory under {@code <root>/<kb>/}
 * @param backend           backend override; null → configured default
 * @param enableVisualIndex visual-index override; null → env default
 * @param metadata          extra payload metadata applied to every file
 * @param onConflict        replace (default) / suffix / reject
 * @param ingest            default true; false = store only, index nothing
 * @param expandArchives    default true; expand {@code .zip} parts into the
 *                          store instead of storing the archive
 */
public record UploadIngestRequest(
        String kbName,
        String kbDescription,
        String subdir,
        String backend,
        Boolean enableVisualIndex,
        Map<String, Object> metadata,
        String onConflict,
        Boolean ingest,
        Boolean expandArchives) {
}
