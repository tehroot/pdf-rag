package org.example.backend.qdrant.fusion;

import org.example.backend.qdrant.PageHit;
import org.example.ingest.SearchHit;

import java.util.List;

/**
 * Pluggable strategy for combining text-side and visual-side hits into a
 * single ranked list. Implementations are CDI beans; pick at query time via
 * {@code ingest.fusion.strategy} (env {@code FUSION_STRATEGY}) or per-call
 * via the {@code fusion_strategy} arg on {@code search_documents}.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link RrfFusion} — rank-based RRF; the default. No score normalization,
 *       robust to scale differences between the two pipelines.
 *   <li>{@link WeightedScoreFusion} — linear combination of normalized scores
 *       with configurable weights. More tunable; sensitive to score floors.
 * </ul>
 *
 * <p>To add a new strategy: implement this interface, mark
 * {@code @ApplicationScoped}, return a unique {@link #name()}. The
 * {@code FusionEngine} discovers it via CDI.
 */
public interface FusionStrategy {

    String name();

    /**
     * Fuse the two ranked lists into one ranked list of {@link SearchHit}s.
     * The output is chunk-shaped — each output hit corresponds to a chunk
     * (so the agent can quote text directly), with page-level information
     * carried through in {@code pageStart} / {@code pageEnd} /
     * {@code pageScore}.
     *
     * @param chunkHits text-pipeline results, already ranked
     * @param pageHits  visual-pipeline results, already ranked
     * @param config    operator-tunable fusion parameters
     * @return ranked list of fused hits, up to {@code config.topK()} entries
     */
    List<SearchHit> fuse(List<SearchHit> chunkHits,
                         List<PageHit> pageHits,
                         FusionConfig config);
}
