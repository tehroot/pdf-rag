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

    /**
     * Ingest with a caller-supplied document id. Backends that key their
     * storage on a deterministic id (Qdrant uses UUIDv5 point ids derived from
     * the doc id) overwrite an existing document's data in place when the same
     * id is supplied again — this is what makes a directory re-scan idempotent.
     * The default ignores {@code explicitDocId}, for backends with no
     * deterministic-id concept (e.g. Open WebUI, which assigns its own file id).
     */
    default IngestResult ingest(IngestRequest req, String explicitDocId) {
        return ingest(req);
    }

    SearchResponse search(SearchRequest req);

    /** List knowledge-base / collection names visible to this backend. */
    List<KnowledgeBaseSummary> listKnowledgeBases();
}
