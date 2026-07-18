package org.hayden.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hayden.backend.qdrant.ChunkPipeline;
import org.hayden.backend.qdrant.ColPaliClient;
import org.hayden.backend.qdrant.ColPaliPipeline;
import org.hayden.backend.qdrant.Embedder;
import org.hayden.backend.qdrant.QdrantClient;
import org.hayden.backend.qdrant.fusion.ConfidenceCalculator;
import org.hayden.backend.qdrant.fusion.FusionEngine;
import org.hayden.backend.qdrant.fusion.FusionStrategy;
import org.hayden.backend.qdrant.fusion.ResultDeduper;
import org.hayden.backend.qdrant.fusion.RrfFusion;
import org.hayden.backend.qdrant.fusion.WeightedScoreFusion;
import org.hayden.ingest.SearchHit;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Retrieval-accuracy eval against a LIVE stack (Qdrant + embedder, plus the
 * ColPali sidecar when the KB has a visual index). Not a unit test — it is
 * skipped unless {@code EVAL_KB} is set. See docs/eval/retrieval-eval.md for
 * the protocol and invocation.
 *
 * <p>Reads {@code EVAL_QUERIES} (JSON: query / gold_filename / gold_page),
 * runs each query, scores page-level hit@1 / hit@K / MRR, and prints a table.
 */
@EnabledIfEnvironmentVariable(named = "EVAL_KB", matches = ".+")
class RetrievalEvalRunner {

    record EvalQuery(String query, String gold_filename, int gold_page) {
    }

    @Test
    void runEval() throws Exception {
        String kb = env("EVAL_KB", null);
        Path queriesPath = Path.of(env("EVAL_QUERIES", "../eval/queries.json"));
        int topK = Integer.parseInt(env("EVAL_TOP_K", "5"));
        String mode = env("EVAL_RETRIEVAL_MODE", "auto");

        List<EvalQuery> queries = new ObjectMapper().readValue(
                Files.readAllBytes(queriesPath), new TypeReference<List<EvalQuery>>() {
                });
        FusionEngine engine = buildEngine();

        int hitAt1 = 0;
        int hitAtK = 0;
        double mrrSum = 0;
        System.out.printf("%n%-50s %6s %8s%n", "query", "rank", "verdict");
        for (EvalQuery q : queries) {
            SearchResponse resp = engine.search(
                    new SearchRequest("qdrant", kb, q.query(), topK, null, mode, null),
                    "qdrant");
            int rank = firstCorrectRank(resp.hits(), q);
            if (rank == 1) hitAt1++;
            if (rank > 0) {
                hitAtK++;
                mrrSum += 1.0 / rank;
            }
            System.out.printf("%-50.50s %6s %8s%n", q.query(),
                    rank > 0 ? String.valueOf(rank) : "-",
                    rank > 0 ? "hit" : "MISS");
        }
        int n = queries.size();
        System.out.printf(Locale.ROOT,
                "%nkb=%s mode=%s topK=%d queries=%d%n"
                        + "hit@1 = %.3f%nhit@%d = %.3f%nMRR   = %.3f%n",
                kb, mode, topK, n,
                n == 0 ? 0 : (double) hitAt1 / n,
                topK, n == 0 ? 0 : (double) hitAtK / n,
                n == 0 ? 0 : mrrSum / n);
    }

    /** 1-based rank of the first page-correct hit, or 0 if none. */
    private static int firstCorrectRank(List<SearchHit> hits, EvalQuery q) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            boolean fileMatch = q.gold_filename().equals(h.filename());
            boolean pageMatch = q.gold_page() >= h.pageStart() && q.gold_page() <= h.pageEnd();
            if (fileMatch && pageMatch) {
                return i + 1;
            }
        }
        return 0;
    }

    // ---- live-stack wiring (mirrors the hand-built-bean test convention) ----

    private static FusionEngine buildEngine() throws Exception {
        ObjectMapper om = new ObjectMapper();

        QdrantClient qdrant = new QdrantClient();
        set(qdrant, "baseUrl", env("QDRANT_URL", "http://localhost:6333"));
        set(qdrant, "apiKey", env("QDRANT_API_KEY", ""));
        set(qdrant, "distance", "Cosine");
        set(qdrant, "connectTimeoutSeconds", 10L);
        set(qdrant, "requestTimeoutSeconds", 120L);
        set(qdrant, "objectMapper", om);
        init(qdrant);

        Embedder embedder = new Embedder();
        set(embedder, "baseUrl", env("EMBED_BASE_URL", "http://localhost:8081/v1"));
        set(embedder, "apiKey", env("EMBED_API_KEY", ""));
        set(embedder, "model", env("EMBED_MODEL", "bge-small-en-v1.5"));
        set(embedder, "batchSize", 64);
        set(embedder, "connectTimeoutSeconds", 10L);
        set(embedder, "requestTimeoutSeconds", 120L);
        set(embedder, "objectMapper", om);
        init(embedder);

        ColPaliClient sidecar = new ColPaliClient();
        set(sidecar, "baseUrl", env("COLPALI_SIDECAR_URL", "http://localhost:8090"));
        set(sidecar, "connectTimeoutSeconds", 10L);
        set(sidecar, "requestTimeoutSeconds", 300L);
        set(sidecar, "batchSize", 8);
        set(sidecar, "objectMapper", om);
        init(sidecar);

        ChunkPipeline chunks = new ChunkPipeline();
        set(chunks, "embedder", embedder);
        set(chunks, "qdrant", qdrant);
        set(chunks, "upsertBatchSize", 128);

        ColPaliPipeline pages = new ColPaliPipeline();
        set(pages, "sidecar", sidecar);
        set(pages, "qdrant", qdrant);
        set(pages, "prefetchMultiplier", 10);
        set(pages, "multivectorUpsertBatchSize", 8);

        ConfidenceCalculator confidence = new ConfidenceCalculator();
        set(confidence, "weightText", 0.4);
        set(confidence, "weightVisual", 0.4);
        set(confidence, "weightAgreement", 0.2);
        set(confidence, "thresholdHigh", 0.7);
        set(confidence, "thresholdMedium", 0.4);
        set(confidence, "textScoreFloor", 1.0);
        set(confidence, "visualScoreFloor", 50.0);

        ResultDeduper deduper = new ResultDeduper();
        set(deduper, "enabled", true);

        FusionEngine engine = new FusionEngine();
        set(engine, "chunks", chunks);
        set(engine, "pages", pages);
        set(engine, "confidence", confidence);
        set(engine, "deduper", deduper);
        set(engine, "strategies",
                new ListInstance<FusionStrategy>(List.of(new RrfFusion(), new WeightedScoreFusion())));
        set(engine, "defaultMode", "auto");
        set(engine, "defaultStrategyName", env("FUSION_STRATEGY", "rrf"));
        set(engine, "rrfK", 60);
        set(engine, "weightedText", 0.5);
        set(engine, "weightedVisual", 0.5);
        set(engine, "textScoreFloor", 1.0);
        set(engine, "visualScoreFloor", 50.0);
        set(engine, "nTextMultiplier", 4);
        set(engine, "nPagesMultiplier", 2);
        set(engine, "debugCandidates", true);
        set(engine, "dedupHeadroom", 2);
        return engine;
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            if (dflt == null) {
                throw new IllegalStateException("env var " + name + " is required");
            }
            return dflt;
        }
        return v;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void init(Object o) throws Exception {
        var m = o.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(o);
    }

    /** Minimal Instance<T> over a fixed list (mirrors the test convention). */
    private static final class ListInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final List<T> items;

        ListInstance(List<T> items) {
            this.items = items;
        }

        @Override public Iterator<T> iterator() { return items.iterator(); }
        @Override public T get() { return items.get(0); }
        @Override public jakarta.enterprise.inject.Instance<T> select(
                java.lang.annotation.Annotation... qualifiers) { return this; }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(
                Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(
                jakarta.enterprise.util.TypeLiteral<U> subtype,
                java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isUnsatisfied() { return items.isEmpty(); }
        @Override public boolean isAmbiguous() { return items.size() > 1; }
        @Override public void destroy(T instance) { }
        @Override public jakarta.enterprise.inject.Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }
        @Override public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
