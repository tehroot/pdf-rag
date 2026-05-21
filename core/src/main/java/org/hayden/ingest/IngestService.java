package org.hayden.ingest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.Backend;
import org.hayden.backend.KnowledgeBaseSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatcher in front of the {@link Backend} implementations. The tool layer
 * calls only this class; it picks a backend per call (explicit arg) or falls
 * back to {@code ingest.backend.default}.
 */
@ApplicationScoped
public class IngestService {

    @Inject
    Instance<Backend> backends;

    @ConfigProperty(name = "ingest.backend.default")
    String defaultBackend;

    public IngestResult ingest(IngestRequest req) {
        return pick(req.backend()).ingest(req);
    }

    public SearchResponse search(SearchRequest req) {
        return pick(req.backend()).search(req);
    }

    public List<KnowledgeBaseSummary> listKnowledgeBases(String backendName) {
        if (backendName == null || backendName.isBlank() || "all".equalsIgnoreCase(backendName)) {
            List<KnowledgeBaseSummary> merged = new ArrayList<>();
            for (Backend b : backends) {
                merged.addAll(b.listKnowledgeBases());
            }
            return merged;
        }
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
