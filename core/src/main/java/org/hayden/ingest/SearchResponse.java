package org.hayden.ingest;

import java.util.List;

/**
 * Response from {@code search_documents}. Carries the ranked hits plus
 * fusion-level provenance:
 *
 * <ul>
 *   <li>{@code fusionMode} — which retrieval mode actually ran, after the
 *       dispatcher resolved {@code retrieval_mode=auto} or applied
 *       fallbacks. Values: {@code "text_only" | "fusion" |
 *       "colpali_only" | "text_only_fallback"}.
 *   <li>{@code confidence} — response-level bucket
 *       ({@code "high" | "medium" | "low"}). Typically the max of per-hit
 *       confidence; null when fusion didn't run.
 *   <li>{@code warnings} — non-fatal degradations the caller should be aware
 *       of (e.g. fusion requested but visual index missing → fell back to
 *       text-only).
 * </ul>
 */
public record SearchResponse(
        String backend,
        String kbName,
        String fusionMode,
        String confidence,
        List<String> warnings,
        List<SearchHit> hits) {

    /** Back-compat: pre-fusion response (text-only) with no warnings. */
    public SearchResponse(String backend, String kbName, List<SearchHit> hits) {
        this(backend, kbName, "text_only", null, List.of(), hits);
    }
}
