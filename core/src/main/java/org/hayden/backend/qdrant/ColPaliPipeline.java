package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.InspectPageResult;
import org.hayden.ingest.SearchRequest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The visual side of the Qdrant backend. Renders each PDF page, sends the
 * rendered images to the ColPali sidecar for multi-vector embedding, stores
 * the PNGs via {@link PageImageStore}, and upserts the embeddings into a
 * {@code <kb>_pages} Qdrant collection configured with named vectors for the
 * prefetch+rerank query pattern.
 *
 * <p>Collaborator pattern: this class does NOT implement {@link org.hayden.backend.Backend}.
 * It's injected into {@link QdrantBackend}, which decides whether to call it
 * (per the {@code enable_visual_index} setting on the KB).
 *
 * <p>Collection naming: a KB named {@code "engineering-docs"} has its page
 * embeddings in a Qdrant collection named {@code "engineering-docs_pages"}.
 * The chunk collection ({@code "engineering-docs"}) and the page collection
 * ({@code "engineering-docs_pages"}) coexist; the existence of the page
 * collection is the implicit signal that visual indexing is enabled for the KB.
 */
@ApplicationScoped
public class ColPaliPipeline {

    static final String PAGES_COLLECTION_SUFFIX = "_pages";

    /** Internal prefetch limit multiplier — pull 10× the final top-N from each pooled vector. */
    static final int PREFETCH_MULTIPLIER = 10;

    /** Default top-K for searchPages when the caller doesn't specify. */
    private static final int DEFAULT_SEARCH_TOP_K = 10;

    @Inject
    PageRasterizer rasterizer;

    @Inject
    TextLayerProbe textLayerProbe;

    @Inject
    ColPaliClient sidecar;

    @Inject
    QdrantClient qdrant;

    @Inject
    PageImageStore imageStore;

    /**
     * True iff this KB has a visual index attached. Used by the orchestrator
     * to gate per-KB whether to also call {@link #ingestPages} at ingest time
     * and {@link #searchPages} at query time.
     */
    public boolean isEnabledFor(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return false;
        }
        return qdrant.getCollection(pagesCollectionName(kbName)) != null;
    }

    /** Pre-flight check: is the sidecar reachable and ready? */
    public boolean sidecarHealthy() {
        return sidecar.isHealthy();
    }

    /**
     * Page count in the {@code <kb>_pages} collection, or null if the KB
     * doesn't have a visual index. Used by {@code list_knowledge_bases}
     * to report visual stats per KB.
     */
    public Long getPageCount(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            return null;
        }
        QdrantClient.CollectionInfo info = qdrant.getCollection(pagesCollectionName(kbName));
        if (info == null) {
            return null;
        }
        return info.points_count;
    }

    /**
     * Rasterize, embed, store images, and upsert page points into the
     * {@code <kb>_pages} collection. The provided {@code docId} must match
     * what {@link ChunkPipeline} used for the same document so chunks and
     * pages can later be joined.
     *
     * <p>Throws {@link IngestException} if the file isn't a PDF, the sidecar
     * is unreachable, or the embeddings don't match expectations.
     */
    public PagesIngestResult ingestPages(IngestRequest req, FetchedFile file, String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IngestException("docId is required for visual ingest");
        }

        // Render every page. Non-PDF inputs throw inside the rasterizer.
        List<PageRasterizer.RenderedPage> rendered = rasterizer.renderAll(file);

        // Probe text quality per page (drives confidence weighting later).
        List<TextLayerProbe.PageQuality> qualities = textLayerProbe.probe(file);
        Map<Integer, Integer> qualityByPage = new LinkedHashMap<>();
        for (TextLayerProbe.PageQuality q : qualities) {
            qualityByPage.put(q.pageNumber(), q.textQuality());
        }

        // Store PNGs out-of-band via PageImageStore and remember the keys.
        List<String> imageKeys = new ArrayList<>(rendered.size());
        for (PageRasterizer.RenderedPage page : rendered) {
            String key = imageStore.store(req.kbName(), docId, page.pageNumber(), page.pngBytes());
            imageKeys.add(key);
        }

        // Build the input batch for the sidecar.
        List<ColPaliClient.PageInput> sidecarInputs = new ArrayList<>(rendered.size());
        for (PageRasterizer.RenderedPage page : rendered) {
            String pageId = docId + ":" + page.pageNumber();
            sidecarInputs.add(new ColPaliClient.PageInput(pageId, page.pngBytes()));
        }
        List<ColPaliClient.PageEmbedding> embeddings = sidecar.embedPages(sidecarInputs);
        if (embeddings.size() != rendered.size()) {
            throw new IngestException("Sidecar returned " + embeddings.size()
                    + " embeddings for " + rendered.size() + " rendered pages");
        }
        int vectorDim = inferVectorDim(embeddings);

        // Ensure the <kb>_pages collection exists with the expected three named vectors.
        String pagesCollection = pagesCollectionName(req.kbName());
        Map<String, QdrantClient.MultiVectorConfig> namedVectors = new LinkedHashMap<>();
        namedVectors.put("original", QdrantClient.MultiVectorConfig.originalRerankOnly(vectorDim));
        namedVectors.put("pooled_rows", QdrantClient.MultiVectorConfig.pooled(vectorDim));
        namedVectors.put("pooled_cols", QdrantClient.MultiVectorConfig.pooled(vectorDim));
        qdrant.ensureMultivectorCollection(pagesCollection, namedVectors);

        // Build multivector points.
        Map<String, Object> userMeta = req.metadata() == null ? Map.of() : req.metadata();
        String modelName = safeModelName();
        List<QdrantClient.MultiVectorPoint> points = new ArrayList<>(rendered.size());
        for (int i = 0; i < rendered.size(); i++) {
            PageRasterizer.RenderedPage page = rendered.get(i);
            ColPaliClient.PageEmbedding emb = embeddings.get(i);
            Map<String, float[][]> vectors = new LinkedHashMap<>();
            vectors.put("original", emb.original());
            vectors.put("pooled_rows", emb.pooledRows());
            vectors.put("pooled_cols", emb.pooledCols());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("doc_id", docId);
            payload.put("page_number", page.pageNumber());
            payload.put("filename", file.filename());
            payload.put("source", req.sourceValue());
            payload.put("source_type", req.sourceType().name().toLowerCase());
            payload.put("content_type", file.contentType());
            payload.put("text_quality",
                    qualityByPage.getOrDefault(page.pageNumber(), 0));
            payload.put("page_image_key", imageKeys.get(i));
            payload.put("page_image_size_bytes", page.pngBytes().length);
            payload.put("page_width", page.width());
            payload.put("page_height", page.height());
            for (Map.Entry<String, Object> e : userMeta.entrySet()) {
                payload.putIfAbsent(e.getKey(), e.getValue());
            }
            payload.put("embed_model", modelName);   // last, can't be overridden

            String pointId = UuidV5.forPage(docId, page.pageNumber());
            points.add(new QdrantClient.MultiVectorPoint(pointId, vectors, payload));
        }

        qdrant.upsertMultivectorPoints(pagesCollection, points);

        return new PagesIngestResult(pagesCollection, docId, rendered.size(), vectorDim);
    }

    /**
     * Vector-search the visual index. Embed the query through the sidecar,
     * run a Qdrant multistage prefetch+rerank query, and map results to
     * {@link PageHit} records.
     */
    public List<PageHit> searchPages(SearchRequest req) {
        if (req.kbName() == null || req.kbName().isBlank()) {
            throw new IngestException("kb_name is required for visual search");
        }
        if (req.query() == null || req.query().isBlank()) {
            throw new IngestException("query is required for visual search");
        }
        int topK = req.topK() <= 0 ? DEFAULT_SEARCH_TOP_K : req.topK();
        int prefetchLimit = topK * PREFETCH_MULTIPLIER;

        float[][] queryVectors = sidecar.embedQuery(req.query());

        List<QdrantClient.PrefetchSpec> prefetches = List.of(
                new QdrantClient.PrefetchSpec("pooled_rows", queryVectors, prefetchLimit),
                new QdrantClient.PrefetchSpec("pooled_cols", queryVectors, prefetchLimit));

        String pagesCollection = pagesCollectionName(req.kbName());
        List<QdrantClient.SearchHitRaw> raw = qdrant.queryMultistage(
                pagesCollection, prefetches, "original", queryVectors, topK, req.filter());

        List<PageHit> out = new ArrayList<>(raw.size());
        for (QdrantClient.SearchHitRaw r : raw) {
            Map<String, Object> p = r.payload() == null ? Map.of() : r.payload();
            out.add(new PageHit(
                    r.score(),
                    asString(p.get("doc_id")),
                    asInt(p.get("page_number")),
                    asString(p.get("filename")),
                    asString(p.get("source")),
                    asInt(p.get("text_quality")),
                    asString(p.get("page_image_key")),
                    p));
        }
        return out;
    }

    /**
     * Drop the visual index for a KB: delete the {@code <kb>_pages} Qdrant
     * collection AND remove the corresponding page images from
     * {@link PageImageStore}.
     */
    public DropResult dropVisualIndex(String kbName) {
        String pagesCollection = pagesCollectionName(kbName);
        boolean collectionDropped = false;
        if (qdrant.getCollection(pagesCollection) != null) {
            qdrant.deleteCollection(pagesCollection);
            collectionDropped = true;
        }
        int filesRemoved = imageStore.deleteForKb(kbName);
        return new DropResult(collectionDropped, filesRemoved);
    }

    /**
     * Retrieve a previously-stored page image by ({@code kbName}, {@code docId},
     * {@code pageNumber}). Used by the {@code inspect_page} tool. Returns null
     * if the page isn't in the visual index or the image has been deleted.
     */
    public byte[] getRenderedPage(String kbName, String docId, int pageNumber) {
        // Derive the storage key from the convention. We could also fetch
        // the point's payload via Qdrant if the layout ever drifts, but for
        // the filesystem impl the convention IS the source of truth.
        String expectedKey = FilesystemPageImageStore.keyFor(kbName, docId, pageNumber);
        return imageStore.retrieve(expectedKey);
    }

    /**
     * Full inspect-page response: PNG bytes (base64 encoded) plus image
     * dimensions, used by the {@code inspect_page} MCP tool. Returns null
     * if the page is missing from the visual index.
     */
    public InspectPageResult inspectPage(String kbName, String docId, int pageNumber) {
        byte[] bytes = getRenderedPage(kbName, docId, pageNumber);
        if (bytes == null) {
            return null;
        }
        int[] dims = readPngDimensions(bytes);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return new InspectPageResult(kbName, docId, pageNumber, dims[0], dims[1], base64);
    }

    /**
     * Decode width and height from a PNG file's IHDR chunk without loading
     * the whole image into a {@code BufferedImage}. PNG layout: 8-byte
     * signature, then IHDR chunk whose first 8 bytes after length/type are
     * width (BE int32) and height (BE int32). We only need the first 24 bytes.
     */
    private static int[] readPngDimensions(byte[] png) {
        if (png.length < 24) {
            throw new IngestException("PNG is too small (" + png.length
                    + " bytes) to read dimensions; the file may be corrupted");
        }
        int width = ((png[16] & 0xff) << 24)
                | ((png[17] & 0xff) << 16)
                | ((png[18] & 0xff) << 8)
                | (png[19] & 0xff);
        int height = ((png[20] & 0xff) << 24)
                | ((png[21] & 0xff) << 16)
                | ((png[22] & 0xff) << 8)
                | (png[23] & 0xff);
        return new int[]{width, height};
    }

    /** Standard naming for the visual index collection corresponding to a KB. */
    public static String pagesCollectionName(String kbName) {
        return kbName + PAGES_COLLECTION_SUFFIX;
    }

    private static int inferVectorDim(List<ColPaliClient.PageEmbedding> embeddings) {
        for (ColPaliClient.PageEmbedding pe : embeddings) {
            if (pe.original() != null && pe.original().length > 0) {
                return pe.original()[0].length;
            }
            if (pe.pooledRows() != null && pe.pooledRows().length > 0) {
                return pe.pooledRows()[0].length;
            }
        }
        throw new IngestException("Sidecar embeddings have no vectors; cannot infer dim");
    }

    private String safeModelName() {
        try {
            ColPaliClient.SidecarInfo info = sidecar.getInfo();
            return info == null || info.model_name == null ? "unknown" : info.model_name;
        } catch (IngestException e) {
            // Sidecar /info is informational; don't fail ingest if it's unreachable
            // mid-flight. We've already proven the sidecar works via embedPages.
            return "unknown";
        }
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

    /** Summary returned from {@link #ingestPages}. */
    public record PagesIngestResult(String collection, String docId,
                                     int pageCount, int vectorDim) {
    }

    /** Summary returned from {@link #dropVisualIndex}. */
    public record DropResult(boolean collectionDropped, int filesRemoved) {
    }
}
