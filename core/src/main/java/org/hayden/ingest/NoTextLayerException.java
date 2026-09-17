package org.hayden.ingest;

/**
 * The document yielded no extractable text — typically a scanned PDF with
 * no text layer. On a text-only ingest this is a hard failure (nothing to
 * index). When a visual index is requested it is NOT a failure: the page
 * embeddings are exactly what makes such a document retrievable, so the
 * backend skips the chunk side and continues with the visual side.
 */
public class NoTextLayerException extends IngestException {

    public NoTextLayerException(String message) {
        super(message);
    }
}
