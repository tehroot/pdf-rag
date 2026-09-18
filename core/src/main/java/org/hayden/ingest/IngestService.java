package org.hayden.ingest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.Backend;
import org.hayden.backend.KnowledgeBaseSummary;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatcher in front of the {@link Backend} implementations. The tool layer
 * calls only this class; it picks a backend per call (explicit arg) or falls
 * back to {@code ingest.backend.default}.
 */
@ApplicationScoped
public class IngestService {

    private static final Logger LOG = Logger.getLogger(IngestService.class);

    @Inject
    Instance<Backend> backends;

    @Inject
    UploadedDocumentStore documentStore;

    @ConfigProperty(name = "ingest.backend.default")
    String defaultBackend;

    public IngestResult ingest(IngestRequest req) {
        return pick(req.backend()).ingest(req);
    }

    /**
     * Ingest with a caller-supplied document id (see
     * {@link Backend#ingest(IngestRequest, String)}). Used by directory scans
     * for idempotent re-ingest via a deterministic per-source-path id.
     */
    public IngestResult ingest(IngestRequest req, String explicitDocId) {
        return pick(req.backend()).ingest(req, explicitDocId);
    }

    public SearchResponse search(SearchRequest req) {
        return pick(req.backend()).search(req);
    }

    /** Delete a document from a KB on the chosen (or default) backend. */
    public DeleteResult deleteDocument(String kbName, String docId, String backendName) {
        return pick(backendName).deleteDocument(kbName, docId);
    }

    /**
     * As above, optionally deleting the document's stored source file too.
     * The file is located by walking the upload store for the doc id — never
     * from a caller-supplied path — and only paths under
     * {@code ingest.upload.root} are ever deleted: a doc ingested from the
     * read-only {@code /docs} / {@code /host} mounts keeps its file and the
     * result message says so.
     */
    public DeleteResult deleteDocument(String kbName, String docId, String backendName,
                                       boolean deleteSource) {
        DeleteResult r = deleteDocument(kbName, docId, backendName);
        if (!deleteSource) {
            return r;
        }
        String suffix;
        var stored = documentStore.findByDocId(kbName, docId);
        if (stored.isPresent() && documentStore.deleteStored(stored.get())) {
            suffix = " Source file deleted: " + stored.get() + ".";
        } else {
            suffix = " Warning: no stored source file under "
                    + documentStore.rootDir() + " for this doc_id "
                    + "(files outside the upload store are never deleted).";
        }
        return new DeleteResult(r.backend(), r.kbName(), r.docId(),
                r.textPointsDeleted(), r.visualPointsDeleted(), r.imagesRemoved(),
                r.message() + suffix);
    }

    /** Delete an entire KB on the chosen (or default) backend. */
    public KbDeleteResult deleteKnowledgeBase(String kbName, String backendName) {
        return pick(backendName).deleteKnowledgeBase(kbName);
    }

    public List<KnowledgeBaseSummary> listKnowledgeBases(String backendName) {
        // Explicit "all" → best-effort merge across every backend, swallowing
        // per-backend errors (e.g. an unconfigured Open WebUI loopback) so the
        // caller still gets the backends that work.
        if ("all".equalsIgnoreCase(backendName)) {
            List<KnowledgeBaseSummary> merged = new ArrayList<>();
            for (Backend b : backends) {
                try {
                    merged.addAll(b.listKnowledgeBases());
                } catch (Exception e) {
                    LOG.warnf("Skipping backend '%s' in list_knowledge_bases (all): %s",
                            b.name(), e.getMessage());
                }
            }
            return merged;
        }
        // Anything else (including null/blank) → use the configured default
        // backend. pick() handles the fallback to ingest.backend.default.
        return pick(backendName).listKnowledgeBases();
    }

    /**
     * KB listing enriched with per-KB distinct-document counts, for the REST
     * status surface ({@code GET /kb}). Count failures degrade to null per KB
     * rather than failing the listing.
     */
    public KnowledgeBaseListResponse listKnowledgeBaseStatuses(String backendName) {
        List<KnowledgeBaseSummary> summaries = listKnowledgeBases(backendName);
        List<KnowledgeBaseStatus> out = new ArrayList<>(summaries.size());
        long totalDocuments = 0;
        for (KnowledgeBaseSummary s : summaries) {
            Long docs = safeDocumentCount(s);
            if (docs != null) {
                totalDocuments += docs;
            }
            out.add(KnowledgeBaseStatus.of(s, docs));
        }
        return new KnowledgeBaseListResponse(out.size(), totalDocuments, out);
    }

    /** One KB's status ({@code GET /kb/{name}}), or null if it doesn't exist. */
    public KnowledgeBaseStatus knowledgeBaseStatus(String backendName, String kbName) {
        for (KnowledgeBaseSummary s : listKnowledgeBases(backendName)) {
            if (s.name().equals(kbName)) {
                return KnowledgeBaseStatus.of(s, safeDocumentCount(s));
            }
        }
        return null;
    }

    private Long safeDocumentCount(KnowledgeBaseSummary s) {
        try {
            return pick(s.backend()).documentCount(s.name());
        } catch (Exception e) {
            LOG.warnf("document count failed for kb=%s backend=%s: %s",
                    s.name(), s.backend(), e.getMessage());
            return null;
        }
    }

    Backend pick(String requested) {
        String wanted = (requested == null || requested.isBlank()) ? defaultBackend : requested;
        for (Backend b : backends) {
            if (b.name().equalsIgnoreCase(wanted)) {
                return b;
            }
        }
        List<String> known = new ArrayList<>();
        for (Backend b : backends) {
            known.add(b.name());
        }
        throw new IngestException("Unknown backend '" + wanted + "' (known: " + known + ")");
    }
}
