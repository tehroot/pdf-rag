package org.example.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.backend.KnowledgeBaseSummary;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;
import org.example.ingest.IngestRequest;
import org.example.ingest.IngestResult;
import org.example.ingest.PageText;
import org.example.ingest.SearchHit;
import org.example.ingest.SearchRequest;
import org.example.ingest.SearchResponse;

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

    /** Qdrant upsert batch size. */
    private static final int UPSERT_BATCH = 128;

    public static final String BACKEND_NAME = "qdrant";

    @Inject
    TextExtractor extractor;

    @Inject
    Chunker chunker;

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
        List<PageText> pages = extractor.extractPerPage(file);
        List<Chunk> chunks = chunker.chunkPerPage(pages);
        if (chunks.isEmpty()) {
            throw new IngestException("Chunker produced 0 chunks for " + file.filename());
        }

        List<String> chunkTexts = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            chunkTexts.add(c.text());
        }
        List<float[]> vectors = embedder.embed(chunkTexts);
        if (vectors.size() != chunks.size()) {
            throw new IngestException("Embedder returned " + vectors.size()
                    + " vectors for " + chunks.size() + " chunks");
        }
        int dim = vectors.get(0).length;

        qdrant.ensureCollection(req.kbName(), dim);

        Map<String, Object> userMeta = req.metadata() == null ? Map.of() : req.metadata();

        String embedModel = embedder.model();
        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Map<String, Object> payload = buildPayload(req, file, docId, c, userMeta, embedModel);
            String pointId = UuidV5.forChunk(docId, i);
            points.add(new QdrantClient.Point(pointId, vectors.get(i), payload));
        }

        for (int i = 0; i < points.size(); i += UPSERT_BATCH) {
            int end = Math.min(i + UPSERT_BATCH, points.size());
            qdrant.upsertPoints(req.kbName(), points.subList(i, end));
        }

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
        float[] qv = embedder.embedOne(req.query());
        int topK = req.topK() <= 0 ? 5 : req.topK();
        List<QdrantClient.SearchHitRaw> raw = qdrant.search(req.kbName(), qv, topK, req.filter());
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

    /** True if the chunks collection for {@code kbName} already exists. */
    public boolean collectionExists(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return false;
        }
        return qdrant.getCollection(kbName) != null;
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
            Long vectors = info == null ? null : info.vectors_count;
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
        for (Map.Entry<String, Object> e : userMeta.entrySet()) {
            payload.putIfAbsent(e.getKey(), e.getValue());
        }
        // embed_model goes in after user metadata so callers can't spoof it.
        payload.put("embed_model", embedModel);
        return payload;
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
