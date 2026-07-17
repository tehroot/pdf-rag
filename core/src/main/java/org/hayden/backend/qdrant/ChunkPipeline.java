package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.KnowledgeBaseSummary;
import org.jboss.logging.Logger;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.PageText;
import org.hayden.ingest.SearchHit;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The text side of the Qdrant backend. Owns the {@code <kb>} chunks collection:
 * per-page text extraction (via Tika or PDFBox), chunking, embedding, and
 * Qdrant upserts/searches. Used by {@link QdrantBackend} as a collaborator.
 *
 * <p>Tagging chunks with their source page range (via
 * {@link Chunker#chunkPerPage(List)}) gives downstream fusion logic a clean
 * join key against the visual pipeline.
 */
@ApplicationScoped
public class ChunkPipeline {

    private static final Logger LOG = Logger.getLogger(ChunkPipeline.class);

    public static final String BACKEND_NAME = "qdrant";

    /**
     * Payload fields indexed for filtering, created idempotently at ingest
     * time right after ensureCollection. doc_id/filename back the search
     * filter path; the integer fields back fusion's chunk↔page join and
     * adjacency checks.
     */
    private static final Map<String, String> INDEXED_PAYLOAD_FIELDS = indexedPayloadFields();

    /** Qdrant upsert batch size. */
    @ConfigProperty(name = "ingest.qdrant.upsert-batch-size", defaultValue = "128")
    int upsertBatchSize;

    /**
     * "sliding" (default) = flat per-page extraction + sliding-window chunker.
     * "structural" = block extraction (headings/lists/tables) + structural
     * packing with heading breadcrumbs; falls back to sliding per-file when
     * structured extraction fails.
     */
    @ConfigProperty(name = "ingest.chunk.strategy", defaultValue = "sliding")
    String chunkStrategy;

    @Inject
    TextExtractor extractor;

    @Inject
    Chunker chunker;

    @Inject
    StructuredExtractor structuredExtractor;

    @Inject
    StructuralChunker structuralChunker;

    @Inject
    Embedder embedder;

    @Inject
    QdrantClient qdrant;

    /** Convenience overload that generates a fresh doc id internally. */
    public IngestResult ingestChunks(IngestRequest req, FetchedFile file) {
        return ingestChunks(req, file, UUID.randomUUID().toString());
    }

    /**
     * Extract → chunk → embed → upsert. Returns an {@link IngestResult} with
     * the document's id and chunk count. The {@code docId} is passed in by the
     * caller so that, when {@link ColPaliPipeline} also runs for the same
     * document, both pipelines use the same doc id for the chunks-to-pages
     * join key in downstream fusion.
     */
    public IngestResult ingestChunks(IngestRequest req, FetchedFile file, String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IngestException("docId is required for chunk ingest");
        }
        long t0 = System.nanoTime();
        List<Chunk> chunks = null;
        int pageCount;
        if ("structural".equalsIgnoreCase(chunkStrategy)) {
            try {
                List<Block> blocks = structuredExtractor.extractBlocks(file);
                chunks = structuralChunker.chunkBlocks(blocks);
            } catch (RuntimeException e) {
                LOG.warnf("Structured extraction failed for %s (%s); "
                        + "falling back to sliding-window chunking",
                        file.filename(), e.getMessage());
            }
        }
        if (chunks == null) {
            List<PageText> pages = extractor.extractPerPage(file);
            chunks = chunker.chunkPerPage(pages);
            pageCount = pages.size();
        } else {
            pageCount = chunks.stream().mapToInt(Chunk::pageEnd).max().orElse(1);
        }
        long tChunk = System.nanoTime();
        if (chunks.isEmpty()) {
            throw new IngestException("Chunker produced 0 chunks for " + file.filename());
        }

        List<String> chunkTexts = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            chunkTexts.add(c.embeddingText());
        }
        List<float[]> vectors = embedder.embed(chunkTexts);
        long tEmbed = System.nanoTime();
        if (vectors.size() != chunks.size()) {
            throw new IngestException("Embedder returned " + vectors.size()
                    + " vectors for " + chunks.size() + " chunks");
        }
        int dim = vectors.get(0).length;

        qdrant.ensureCollection(req.kbName(), dim);
        qdrant.ensurePayloadIndexes(req.kbName(), INDEXED_PAYLOAD_FIELDS);

        Map<String, Object> userMeta = req.metadata() == null ? Map.of() : req.metadata();

        String embedModel = embedder.model();
        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Map<String, Object> payload = buildPayload(req, file, docId, c, userMeta, embedModel);
            String pointId = UuidV5.forChunk(docId, i);
            points.add(new QdrantClient.Point(pointId, vectors.get(i), payload));
        }

        if (upsertBatchSize <= 0) {
            throw new IngestException("ingest.qdrant.upsert-batch-size must be > 0 (got "
                    + upsertBatchSize + ")");
        }
        for (int i = 0; i < points.size(); i += upsertBatchSize) {
            int end = Math.min(i + upsertBatchSize, points.size());
            qdrant.upsertPoints(req.kbName(), points.subList(i, end));
        }
        long tUpsert = System.nanoTime();

        LOG.infof("ingest text kb=%s doc=%s file=%s strategy=%s pages=%d chunks=%d "
                        + "extract+chunk=%dms embed=%dms upsert=%dms",
                req.kbName(), docId, file.filename(), chunkStrategy, pageCount, chunks.size(),
                ms(t0, tChunk), ms(tChunk, tEmbed), ms(tEmbed, tUpsert));

        return new IngestResult(BACKEND_NAME, req.kbName(), req.kbName(), docId,
                "completed", chunks.size(), true,
                "Ingested " + chunks.size() + " chunks into Qdrant collection '"
                        + req.kbName() + "' (doc_id=" + docId + ")");
    }

    public SearchResponse searchChunks(SearchRequest req) {
        if (req.kbName() == null || req.kbName().isBlank()) {
            throw new IngestException("kb_name is required for search");
        }
        if (req.query() == null || req.query().isBlank()) {
            throw new IngestException("query is required for search");
        }
        long t0 = System.nanoTime();
        float[] qv = embedder.embedOne(req.query());
        long tEmbed = System.nanoTime();
        int topK = req.topK() <= 0 ? 5 : req.topK();
        List<QdrantClient.SearchHitRaw> raw = qdrant.search(req.kbName(), qv, topK, req.filter());
        LOG.debugf("search text kb=%s topK=%d embed=%dms qdrant=%dms hits=%d",
                req.kbName(), topK, ms(t0, tEmbed), ms(tEmbed, System.nanoTime()), raw.size());
        List<SearchHit> hits = new ArrayList<>(raw.size());
        for (QdrantClient.SearchHitRaw r : raw) {
            Map<String, Object> p = r.payload() == null ? Map.of() : r.payload();
            int pageStart = asInt(p.get("page_start"));
            int pageEnd = asInt(p.get("page_end"));
            // Older payloads (pre-page-tagging) won't have page_start/page_end;
            // default to (1, 1) so the records still validate.
            if (pageStart < 1) pageStart = 1;
            if (pageEnd < pageStart) pageEnd = pageStart;
            hits.add(new SearchHit(
                    r.score(),
                    asString(p.get("text")),
                    asString(p.get("source")),
                    asString(p.get("filename")),
                    asString(p.get("doc_id")),
                    asInt(p.get("chunk_index")),
                    pageStart,
                    pageEnd,
                    r.score(),    // textScore = raw cosine
                    null,         // pageScore filled by fusion (if it runs)
                    null,         // confidence filled by ConfidenceCalculator
                    p));
        }
        return new SearchResponse(BACKEND_NAME, req.kbName(), hits);
    }

    /**
     * Delete a document's chunks from the {@code <kb>} collection. Returns true
     * if the collection existed (delete issued). Idempotent — a docId with no
     * points is a no-op.
     */
    public boolean deleteDoc(String kbName, String docId) {
        if (kbName == null || kbName.isBlank() || docId == null || docId.isBlank()) {
            return false;
        }
        return qdrant.deleteByDocId(kbName, docId);
    }

    /** True if the chunks collection for {@code kbName} already exists. */
    public boolean collectionExists(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return false;
        }
        return qdrant.getCollection(kbName) != null;
    }

    /**
     * Distinct document count in the KB's chunk collection (facet on the
     * payload-indexed {@code doc_id}), or null if the collection doesn't exist.
     */
    public Long countDocuments(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return null;
        }
        return qdrant.countDocuments(kbName);
    }

    public List<KnowledgeBaseSummary> listKbCollections() {
        List<KnowledgeBaseSummary> out = new ArrayList<>();
        for (QdrantClient.CollectionSummary cs : qdrant.listCollections()) {
            // Skip <kb>_pages collections in the listing — they're an implementation
            // detail of the visual index, surfaced via the visual_index_enabled flag
            // on the corresponding chunk collection (added in Phase 6.2).
            if (cs.name().endsWith("_pages")) {
                continue;
            }
            QdrantClient.CollectionInfo info = qdrant.getCollection(cs.name());
            // points_count, not vectors_count: Qdrant 1.10+ returns
            // vectors_count as null (lazily computed, deprecated), which
            // parses to 0 and reports every KB as empty. Chunk collections
            // are single-vector, so points == chunks == vectors.
            Long vectors = info == null ? null : info.points_count;
            Integer dim = info == null ? null : info.dim();
            out.add(new KnowledgeBaseSummary(BACKEND_NAME, cs.name(), cs.name(), vectors, dim));
        }
        return out;
    }

    private static Map<String, Object> buildPayload(IngestRequest req, FetchedFile file,
                                                    String docId, Chunk c,
                                                    Map<String, Object> userMeta,
                                                    String embedModel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", c.text());
        payload.put("doc_id", docId);
        payload.put("chunk_index", c.index());
        payload.put("page_start", c.pageStart());
        payload.put("page_end", c.pageEnd());
        payload.put("filename", file.filename());
        payload.put("source", req.sourceValue());
        payload.put("source_type", req.sourceType().name().toLowerCase());
        payload.put("content_type", file.contentType());
        payload.put("char_start", c.startOffset());
        payload.put("char_end", c.endOffset());
        if (c.headingPath() != null && !c.headingPath().isEmpty()) {
            payload.put("heading_path", c.headingPath());
        }
        for (Map.Entry<String, Object> e : userMeta.entrySet()) {
            payload.putIfAbsent(e.getKey(), e.getValue());
        }
        // embed_model goes in after user metadata so callers can't spoof it.
        payload.put("embed_model", embedModel);
        return payload;
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private static Map<String, String> indexedPayloadFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("doc_id", "keyword");
        m.put("filename", "keyword");
        m.put("chunk_index", "integer");
        m.put("page_start", "integer");
        m.put("page_end", "integer");
        return m;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
