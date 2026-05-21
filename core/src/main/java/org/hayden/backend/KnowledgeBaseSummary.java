package org.hayden.backend;

/**
 * One row in the {@code list_knowledge_bases} response.
 *
 * <p>{@code vectors} / {@code dim} report the chunk-side single-vector
 * collection's stats. {@code visualIndexEnabled} flags whether the KB also
 * has a ColPali page index (a sibling {@code <kb>_pages} collection); when
 * true, {@code visualIndexPages} reports the page count.
 */
public record KnowledgeBaseSummary(
        String backend,
        String id,
        String name,
        Long vectors,
        Integer dim,
        Boolean visualIndexEnabled,
        Long visualIndexPages) {

    /** Back-compat: chunk-only summary, no visual info. */
    public KnowledgeBaseSummary(String backend, String id, String name, Long vectors, Integer dim) {
        this(backend, id, name, vectors, dim, false, null);
    }

    /** Convenience: rebuild this summary with visual-index info attached. */
    public KnowledgeBaseSummary withVisualIndex(boolean enabled, Long pages) {
        return new KnowledgeBaseSummary(backend, id, name, vectors, dim, enabled, pages);
    }
}
