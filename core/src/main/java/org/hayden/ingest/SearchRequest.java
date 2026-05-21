package org.hayden.ingest;

import java.util.Map;

/**
 * Search request shape. {@code retrievalMode} is resolved by {@code FusionEngine}:
 *
 * <ul>
 *   <li>{@code null} or {@code "auto"} (default) — use fusion when the KB has
 *       a visual index; fall back to text-only otherwise.
 *   <li>{@code "fusion"} — require both pipelines; if visual index missing,
 *       fall back to text-only with a warning.
 *   <li>{@code "text_only"} — chunk pipeline only; cheap and fast.
 *   <li>{@code "colpali_only"} — page pipeline only; requires visual index.
 * </ul>
 *
 * <p>{@code fusionStrategy} is an operator override ({@code "rrf"} or
 * {@code "weighted"}); null falls back to the configured default.
 */
public record SearchRequest(
        String backend,
        String kbName,
        String query,
        int topK,
        Map<String, Object> filter,
        String retrievalMode,
        String fusionStrategy) {

    /** Back-compat: no retrieval-mode or fusion-strategy override. */
    public SearchRequest(String backend, String kbName, String query, int topK,
                         Map<String, Object> filter) {
        this(backend, kbName, query, topK, filter, null, null);
    }
}
