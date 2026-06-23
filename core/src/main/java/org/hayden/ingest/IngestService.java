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
