package org.hayden.backend.qdrant.fusion;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.qdrant.ChunkPipeline;
import org.hayden.backend.qdrant.ColPaliPipeline;
import org.hayden.backend.qdrant.PageHit;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.SearchHit;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code retrieval_mode} (auto / fusion / text_only / colpali_only),
 * runs the appropriate pipelines, applies the configured fusion strategy,
 * and attaches confidence labels. Returns a fully-populated
 * {@link SearchResponse}.
 *
 * <p>Resolution rules (matches the plan's fallback matrix):
 *
 * <table border="1">
 *   <tr><th>retrieval_mode</th><th>KB has visual index?</th><th>What runs</th></tr>
 *   <tr><td>auto (default)</td><td>yes</td><td>fusion</td></tr>
 *   <tr><td>auto (default)</td><td>no</td><td>text_only</td></tr>
 *   <tr><td>fusion</td><td>yes</td><td>fusion</td></tr>
 *   <tr><td>fusion</td><td>no</td><td>text_only_fallback (warning)</td></tr>
 *   <tr><td>text_only</td><td>any</td><td>text_only</td></tr>
 *   <tr><td>colpali_only</td><td>yes</td><td>colpali_only</td></tr>
 *   <tr><td>colpali_only</td><td>no</td><td>error</td></tr>
 * </table>
 *
 * <p>Sidecar-down behavior at query time: silently degrade to text_only with
 * a warning in the response. (Ingest is the strict side — see
 * {@code QdrantBackend.ingest}.)
 */
@ApplicationScoped
public class FusionEngine {

    private static final Logger LOG = Logger.getLogger(FusionEngine.class);

    public static final String MODE_AUTO = "auto";
    public static final String MODE_FUSION = "fusion";
    public static final String MODE_TEXT_ONLY = "text_only";
    public static final String MODE_COLPALI_ONLY = "colpali_only";
    public static final String MODE_FALLBACK = "text_only_fallback";

    @Inject
    ChunkPipeline chunks;

    @Inject
    ColPaliPipeline pages;

    @Inject
    Instance<FusionStrategy> strategies;

    @Inject
    ConfidenceCalculator confidence;

    @Inject
    ResultDeduper deduper;

    @ConfigProperty(name = "ingest.retrieval.default_mode", defaultValue = "auto")
    String defaultMode;

    @ConfigProperty(name = "ingest.fusion.strategy", defaultValue = "rrf")
    String defaultStrategyName;

    @ConfigProperty(name = "ingest.fusion.rrf.k", defaultValue = "60")
    int rrfK;

    @ConfigProperty(name = "ingest.fusion.weighted.text", defaultValue = "0.5")
    double weightedText;

    @ConfigProperty(name = "ingest.fusion.weighted.visual", defaultValue = "0.5")
    double weightedVisual;

    @ConfigProperty(name = "ingest.fusion.weighted.text_score_floor", defaultValue = "1.0")
    double textScoreFloor;

    @ConfigProperty(name = "ingest.fusion.weighted.visual_score_floor", defaultValue = "50.0")
    double visualScoreFloor;

    @ConfigProperty(name = "ingest.search.n_text_multiplier", defaultValue = "4")
    int nTextMultiplier;

    @ConfigProperty(name = "ingest.search.n_pages_multiplier", defaultValue = "2")
    int nPagesMultiplier;

    /**
     * When true, pre-fusion candidate lists and post-fusion results are logged
     * at INFO (visible without touching log levels) — the substrate for
     * retrieval-accuracy debugging. When false, the same lines go to DEBUG and
     * can be enabled via the
     * {@code org.hayden.backend.qdrant.fusion} log category.
     */
    @ConfigProperty(name = "ingest.search.debug-candidates", defaultValue = "false")
    boolean debugCandidates;

    /**
     * Over-fetch factor for dedup: strategies return top-K × headroom hits so
     * that collapsing duplicates can backfill from next-ranked candidates.
     * Only applied while dedup is enabled.
     */
    @ConfigProperty(name = "ingest.search.dedup.headroom", defaultValue = "2")
    int dedupHeadroom;

    /**
     * The single entry point. Resolves the mode, calls pipelines, fuses,
     * annotates with confidence.
     */
    public SearchResponse search(SearchRequest req, String kbBackend) {
        String requested = req.retrievalMode() == null || req.retrievalMode().isBlank()
                ? defaultMode
                : req.retrievalMode();
        int topK = req.topK() <= 0 ? 5 : req.topK();
        boolean visualAvailable = pages.isEnabledFor(req.kbName());

        List<String> warnings = new ArrayList<>();
        String mode = resolveMode(requested, visualAvailable, warnings);

        return switch (mode) {
            case MODE_TEXT_ONLY -> textOnly(req, topK, mode, warnings, kbBackend);
            case MODE_FALLBACK -> textOnly(req, topK, MODE_FALLBACK, warnings, kbBackend);
            case MODE_COLPALI_ONLY -> colpaliOnly(req, topK, warnings, kbBackend);
            case MODE_FUSION -> fusion(req, topK, warnings, kbBackend);
            default -> throw new IngestException("Internal: unresolved mode '" + mode + "'");
        };
    }

    /** Public for testing — resolution logic only. */
    public String resolveMode(String requested, boolean visualAvailable, List<String> warnings) {
        return switch (requested.toLowerCase()) {
            case MODE_AUTO -> visualAvailable ? MODE_FUSION : MODE_TEXT_ONLY;
            case MODE_FUSION -> {
                if (visualAvailable) yield MODE_FUSION;
                warnings.add("retrieval_mode=fusion requested but KB has no visual index; "
                        + "falling back to text_only");
                yield MODE_FALLBACK;
            }
            case MODE_TEXT_ONLY -> MODE_TEXT_ONLY;
            case MODE_COLPALI_ONLY -> {
                if (visualAvailable) yield MODE_COLPALI_ONLY;
                throw new IngestException(
                        "retrieval_mode=colpali_only requires a visual index, "
                                + "but KB has none. Re-ingest with enable_visual_index=true "
                                + "or use retrieval_mode=text_only.");
            }
            default -> throw new IngestException(
                    "Unknown retrieval_mode '" + requested + "' (expected auto | fusion | "
                            + "text_only | colpali_only)");
        };
    }

    // ---- mode handlers ------------------------------------------------------

    private SearchResponse textOnly(SearchRequest req, int topK, String mode,
                                     List<String> warnings, String kbBackend) {
        long t0 = System.nanoTime();
        SearchResponse textOnlyResponse = chunks.searchChunks(withTopK(req, fetchK(topK)));
        List<SearchHit> hits = deduper.collapse(textOnlyResponse.hits(), topK);
        ConfidenceCalculator.Result conf = confidence.annotate(hits, hits, List.of());
        logTextCandidates(conf.hits());
        LOG.infof("search kb=%s mode=%s topK=%d hits=%d textMs=%d confidence=%s warnings=%d",
                req.kbName(), mode, topK, hits.size(), ms(t0, System.nanoTime()),
                conf.responseConfidence(), warnings.size());
        return new SearchResponse(kbBackend, req.kbName(), mode,
                conf.responseConfidence(), warnings, conf.hits());
    }

    private SearchResponse colpaliOnly(SearchRequest req, int topK,
                                        List<String> warnings, String kbBackend) {
        List<PageHit> pageHits;
        try {
            pageHits = pages.searchPages(req);
        } catch (IngestException e) {
            // Sidecar-down at query time → soft-degrade to text-only.
            warnings.add("ColPali sidecar unreachable; degraded to text_only. " + e.getMessage());
            return textOnly(req, topK, MODE_FALLBACK, warnings, kbBackend);
        }
        // Promote page hits to chunk-shaped SearchHits (no text).
        List<SearchHit> hits = new ArrayList<>(pageHits.size());
        for (PageHit ph : pageHits) {
            hits.add(new SearchHit(
                    ph.score(),
                    null,
                    ph.source(),
                    ph.filename(),
                    ph.docId(),
                    -1,
                    ph.pageNumber(),
                    ph.pageNumber(),
                    null,
                    ph.score(),
                    null,
                    ph.payload()));
        }
        ConfidenceCalculator.Result conf = confidence.annotate(hits, List.of(), pageHits);
        logPageCandidates(pageHits);
        LOG.infof("search kb=%s mode=%s topK=%d hits=%d confidence=%s warnings=%d",
                req.kbName(), MODE_COLPALI_ONLY, topK, pageHits.size(),
                conf.responseConfidence(), warnings.size());
        return new SearchResponse(kbBackend, req.kbName(), MODE_COLPALI_ONLY,
                conf.responseConfidence(), warnings, conf.hits());
    }

    private SearchResponse fusion(SearchRequest req, int topK,
                                   List<String> warnings, String kbBackend) {
        // Pull deeper from each pipeline than top-K so RRF can join broadly.
        int nText = topK * nTextMultiplier;
        int nPages = topK * nPagesMultiplier;
        SearchRequest textReq = withTopK(req, nText);
        SearchRequest pageReq = withTopK(req, nPages);

        long t0 = System.nanoTime();
        SearchResponse textResp = chunks.searchChunks(textReq);
        List<SearchHit> chunkHits = textResp.hits();
        long tText = System.nanoTime();

        List<PageHit> pageHits;
        try {
            pageHits = pages.searchPages(pageReq);
        } catch (IngestException e) {
            warnings.add("ColPali sidecar unreachable mid-search; degraded to text_only. "
                    + e.getMessage());
            return textOnly(req, topK, MODE_FALLBACK, warnings, kbBackend);
        }
        long tVisual = System.nanoTime();

        logTextCandidates(chunkHits);
        logPageCandidates(pageHits);

        FusionStrategy strategy = pickStrategy(req.fusionStrategy());
        FusionConfig cfg = buildConfig(fetchK(topK));
        List<SearchHit> fused = strategy.fuse(chunkHits, pageHits, cfg);
        fused = deduper.collapse(fused, topK);
        ConfidenceCalculator.Result conf = confidence.annotate(fused, chunkHits, pageHits);
        long tFuse = System.nanoTime();

        logFusedResults(conf.hits());
        LOG.infof("search kb=%s mode=%s strategy=%s topK=%d nText=%d/%d nPages=%d/%d "
                        + "textMs=%d visualMs=%d fuseMs=%d confidence=%s warnings=%d",
                req.kbName(), MODE_FUSION, strategy.name(), topK,
                chunkHits.size(), nText, pageHits.size(), nPages,
                ms(t0, tText), ms(tText, tVisual), ms(tVisual, tFuse),
                conf.responseConfidence(), warnings.size());

        return new SearchResponse(kbBackend, req.kbName(), MODE_FUSION,
                conf.responseConfidence(), warnings, conf.hits());
    }

    // ---- candidate logging (retrieval-accuracy debugging substrate) ----------

    private boolean candidatesLoggable() {
        return debugCandidates || LOG.isDebugEnabled();
    }

    private void candidateLine(String fmt, Object... args) {
        if (debugCandidates) {
            LOG.infof(fmt, args);
        } else {
            LOG.debugf(fmt, args);
        }
    }

    private void logTextCandidates(List<SearchHit> hits) {
        if (!candidatesLoggable()) return;
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            candidateLine("text[%d] score=%.4f doc=%s chunk=%d pages=%d-%d \"%s\"",
                    i, h.score(), h.docId(), h.chunkIndex(), h.pageStart(), h.pageEnd(),
                    snippet(h.text()));
        }
    }

    private void logPageCandidates(List<PageHit> hits) {
        if (!candidatesLoggable()) return;
        for (int i = 0; i < hits.size(); i++) {
            PageHit h = hits.get(i);
            candidateLine("page[%d] score=%.2f doc=%s page=%d tq=%d file=%s",
                    i, h.score(), h.docId(), h.pageNumber(), h.textQuality(), h.filename());
        }
    }

    private void logFusedResults(List<SearchHit> hits) {
        if (!candidatesLoggable()) return;
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            candidateLine("fused[%d] score=%.5f text=%s page=%s conf=%s doc=%s chunk=%d pages=%d-%d",
                    i, h.score(), fmtScore(h.textScore()), fmtScore(h.pageScore()),
                    h.confidence(), h.docId(), h.chunkIndex(), h.pageStart(), h.pageEnd());
        }
    }

    private static String fmtScore(Double s) {
        return s == null ? "-" : String.format("%.4f", s);
    }

    private static String snippet(String text) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private FusionStrategy pickStrategy(String requested) {
        String wanted = (requested == null || requested.isBlank()) ? defaultStrategyName : requested;
        for (FusionStrategy s : strategies) {
            if (s.name().equalsIgnoreCase(wanted)) {
                return s;
            }
        }
        List<String> known = new ArrayList<>();
        for (FusionStrategy s : strategies) known.add(s.name());
        throw new IngestException("Unknown fusion strategy '" + wanted + "' (known: " + known + ")");
    }

    private FusionConfig buildConfig(int topK) {
        return new FusionConfig(topK, rrfK, weightedText, weightedVisual,
                textScoreFloor, visualScoreFloor);
    }

    /** How many hits to pull/fuse before dedup collapses back to topK. */
    private int fetchK(int topK) {
        return deduper.enabled() ? topK * Math.max(1, dedupHeadroom) : topK;
    }

    private static SearchRequest withTopK(SearchRequest req, int newTopK) {
        return new SearchRequest(req.backend(), req.kbName(), req.query(), newTopK,
                req.filter(), req.retrievalMode(), req.fusionStrategy());
    }
}
