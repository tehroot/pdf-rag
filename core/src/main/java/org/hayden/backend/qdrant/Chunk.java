package org.hayden.backend.qdrant;

import java.util.List;

/**
 * One chunk of extracted text bound for the embedder and the Qdrant payload.
 *
 * <p>{@code text} is what gets stored in the payload (and returned in search
 * hits). {@code embeddingTextOverride}, when non-null, is what gets embedded
 * instead — the structural chunker uses it to prepend a heading-breadcrumb
 * prefix that gives the embedding model section context without polluting the
 * stored chunk text. {@code headingPath} is the chunk's section ancestry
 * (outermost first), stored in the payload as {@code heading_path}.
 */
public record Chunk(int index, int startOffset, int endOffset, String text,
                    int pageStart, int pageEnd,
                    String embeddingTextOverride, List<String> headingPath) {

    /**
     * Back-compat constructor for callers that don't track page numbers.
     * Treats the chunk as belonging to page 1 of a single-page document.
     */
    public Chunk(int index, int startOffset, int endOffset, String text) {
        this(index, startOffset, endOffset, text, 1, 1, null, null);
    }

    /** Back-compat constructor for chunks without structural metadata. */
    public Chunk(int index, int startOffset, int endOffset, String text,
                 int pageStart, int pageEnd) {
        this(index, startOffset, endOffset, text, pageStart, pageEnd, null, null);
    }

    /** The text sent to the embedder. Defaults to the stored {@link #text()}. */
    public String embeddingText() {
        return embeddingTextOverride == null ? text : embeddingTextOverride;
    }
}
