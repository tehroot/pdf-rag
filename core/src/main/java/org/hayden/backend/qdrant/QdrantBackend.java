package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.Backend;
import org.hayden.backend.KnowledgeBaseSummary;
import org.hayden.backend.qdrant.fusion.FusionEngine;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.FileFetcher;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator for the Qdrant backend. Delegates text-side work to
 * {@link ChunkPipeline} and (when visual indexing is enabled for the KB)
 * delegates the page-side work to {@link ColPaliPipeline}. Both pipelines
 * share a doc id so chunks and pages join cleanly at fusion time.
 *
 * <p>Sync vs queued routing: ingest calls for large PDFs (page count above
 * {@code ingest.queue.sync_threshold_pages}, default 20) get queued for
 * background processing. The {@link org.hayden.jobs.IngestWorker} picks
 * them up and calls back into {@link #ingestForWorker(IngestJob)}. Smaller
 * files run synchronously so the agent gets results immediately.
 */
@ApplicationScoped
public class QdrantBackend implements Backend {

    public static final String NAME = "qdrant";

    @Inject
    FileFetcher fetcher;

    @Inject
    ChunkPipeline chunks;

    @Inject
    ColPaliPipeline pages;

    @Inject
    FusionEngine fusion;

    @Inject
    IngestQueue queue;

    @ConfigProperty(name = "ingest.visual_index.default_enabled", defaultValue = "true")
    boolean defaultVisualIndexEnabled;

    @ConfigProperty(name = "ingest.queue.sync_threshold_pages", defaultValue = "20")
    int syncThresholdPages;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public IngestResult ingest(IngestRequest req) {
        FetchedFile file = fetch(req);
        String docId = UUID.randomUUID().toString();

        boolean visualRequested = resolveVisualIndexEnabled(req);
        validateModeConsistency(req.kbName(), visualRequested);

        // Pre-flight sidecar check when visual is requested — hard-fail rather
        // than silently degrading to text-only for an explicit visual request.
        if (visualRequested && !pages.sidecarHealthy()) {
            throw new IngestException(
                    "Visual index requested for KB '" + req.kbName()
                            + "' but the ColPali sidecar is unreachable. "
                            + "Retry when the sidecar is available, or call again with "
                            + "enable_visual_index=false to skip the visual side.");
        }

        // Async routing: big PDFs that will take minutes to embed go to the queue
        // so the agent isn't blocked. Everything else runs synchronously.
        if (shouldQueue(file, visualRequested)) {
            IngestJob job = IngestJob.queued(req, docId);
            queue.submit(job);
            return IngestResult.queued(NAME, req.kbName(), docId, job.jobId());
        }

        return doIngest(req, file, docId, visualRequested);
    }

    /**
     * Worker entry point: re-fetch the file from the persisted request,
     * re-validate mode consistency (the KB's state may have changed between
     * submit and worker pickup), and run the actual ingest. Used by
     * {@link org.hayden.jobs.IngestWorker}.
     */
    public IngestResult ingestForWorker(IngestJob job) {
        IngestRequest req = job.request();
        FetchedFile file = fetch(req);
        boolean visualRequested = resolveVisualIndexEnabled(req);
        validateModeConsistency(req.kbName(), visualRequested);
        if (visualRequested && !pages.sidecarHealthy()) {
            throw new IngestException(
                    "Visual index requested for KB '" + req.kbName()
                            + "' but the ColPali sidecar is unreachable.");
        }
        return doIngest(req, file, job.docId(), visualRequested);
    }

    /** The actual ingest work. Shared by sync path and worker path. */
    IngestResult doIngest(IngestRequest req, FetchedFile file, String docId,
                          boolean visualRequested) {
        // Text ingest always runs.
        IngestResult chunkResult = chunks.ingestChunks(req, file, docId);

        List<String> warnings = new ArrayList<>();
        int pageCount = 0;
        if (visualRequested) {
            if (isPdf(file)) {
                ColPaliPipeline.PagesIngestResult pagesResult =
                        pages.ingestPages(req, file, docId);
                pageCount = pagesResult.pageCount();
            } else {
                warnings.add("enable_visual_index=true but file is not a PDF; "
                        + "visual side skipped for this document. "
                        + "Text chunks still ingested.");
            }
        }

        String message = chunkResult.message();
        if (pageCount > 0) {
            message += " + " + pageCount + " pages visual-indexed";
        }

        return new IngestResult(
                NAME,
                chunkResult.kbId(),
                chunkResult.kbName(),
                docId,
                chunkResult.processingStatus(),
                chunkResult.chunkCount(),
                pageCount,
                chunkResult.addedToKb(),
                message,
                warnings,
                null);
    }

    @Override
    public SearchResponse search(SearchRequest req) {
        return fusion.search(req, NAME);
    }

    @Override
    public List<KnowledgeBaseSummary> listKnowledgeBases() {
        List<KnowledgeBaseSummary> textOnly = chunks.listKbCollections();
        List<KnowledgeBaseSummary> augmented = new ArrayList<>(textOnly.size());
        for (KnowledgeBaseSummary kb : textOnly) {
            boolean visualEnabled = pages.isEnabledFor(kb.name());
            Long visualPages = visualEnabled ? pages.getPageCount(kb.name()) : null;
            augmented.add(kb.withVisualIndex(visualEnabled, visualPages));
        }
        return augmented;
    }

    /**
     * Deterministic UUID v5 for chunk point IDs. Preserved as a static helper
     * for back-compat with tests; new code should use {@link UuidV5#forChunk}.
     */
    public static String pointIdFor(String docId, int chunkIndex) {
        return UuidV5.forChunk(docId, chunkIndex);
    }

    /** Resolve the effective visual-index flag for this request. */
    boolean resolveVisualIndexEnabled(IngestRequest req) {
        if (req.enableVisualIndex() != null) {
            return req.enableVisualIndex();
        }
        return defaultVisualIndexEnabled;
    }

    /**
     * Reject mode mismatch on an existing KB: if the KB has a visual index but
     * this call says no visual (or vice versa), throw with a clear message.
     * Fresh KBs (neither chunk nor visual collection exists yet) pass through
     * unchanged regardless of the requested mode.
     */
    void validateModeConsistency(String kbName, boolean visualRequested) {
        boolean kbHasChunks = chunks.collectionExists(kbName);
        boolean kbHasVisual = pages.isEnabledFor(kbName);
        if (!kbHasChunks && !kbHasVisual) {
            // Fresh KB; either mode is fine for the first ingest.
            return;
        }
        if (kbHasVisual && !visualRequested) {
            throw new IngestException(
                    "KB '" + kbName + "' was created with visual index enabled, "
                            + "but this call has enable_visual_index=false. "
                            + "Either set enable_visual_index=true or create a new KB.");
        }
        if (!kbHasVisual && visualRequested) {
            throw new IngestException(
                    "KB '" + kbName + "' was created without a visual index, "
                            + "but this call has enable_visual_index=true. "
                            + "Either set enable_visual_index=false or create a new KB.");
        }
    }

    /**
     * Routing heuristic for sync vs queue.
     *
     * <p>Conservative: only queue PDFs that have visual indexing enabled AND
     * exceed the configured page-count threshold. Text-only ingest is fast
     * enough to run synchronously; non-PDF files have no page count to
     * compare against. The agent gets immediate results in the common case;
     * only the genuinely-slow ingests are queued.
     */
    boolean shouldQueue(FetchedFile file, boolean visualRequested) {
        if (!visualRequested) return false;
        if (!isPdf(file)) return false;
        if (syncThresholdPages <= 0) return false;
        int pages = countPdfPages(file);
        return pages >= syncThresholdPages;
    }

    private static int countPdfPages(FetchedFile file) {
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return 0;   // unknown; treat as small.
        }
    }

    private FetchedFile fetch(IngestRequest req) {
        return switch (req.sourceType()) {
            case URL -> fetcher.fromUrl(req.sourceValue(), req.filename());
            case PATH -> fetcher.fromPath(req.sourceValue(), req.filename());
            case INLINE -> fetcher.fromInline(req.sourceValue(), req.filename());
        };
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }
}
