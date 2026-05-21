package org.hayden.ingest;

/**
 * Plain-text content extracted from a single page of a multi-page document,
 * carrying its source page number so downstream chunks can be tagged.
 *
 * <p>Page numbers are 1-indexed to match how humans and PDF tooling
 * conventionally refer to pages.
 */
public record PageText(int pageNumber, String text) {
}
