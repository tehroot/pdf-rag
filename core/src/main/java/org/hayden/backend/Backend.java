package org.hayden.backend;

import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;

import java.util.List;

/**
 * A storage target for ingested documents. Implementations are CDI beans; the
 * dispatcher picks one by {@link #name()} per call (or via {@code ingest.backend.default}).
 */
public interface Backend {

    String name();

    IngestResult ingest(IngestRequest req);

    SearchResponse search(SearchRequest req);

    /** List knowledge-base / collection names visible to this backend. */
    List<KnowledgeBaseSummary> listKnowledgeBases();
}
