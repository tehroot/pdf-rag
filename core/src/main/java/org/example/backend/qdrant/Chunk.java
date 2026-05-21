package org.example.backend.qdrant;

public record Chunk(int index, int startOffset, int endOffset, String text,
                    int pageStart, int pageEnd) {

    /**
     * Back-compat constructor for callers that don't track page numbers.
     * Treats the chunk as belonging to page 1 of a single-page document.
     */
    public Chunk(int index, int startOffset, int endOffset, String text) {
        this(index, startOffset, endOffset, text, 1, 1);
    }
}
