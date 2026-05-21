package org.example.backend.qdrant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.ingest.IngestException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain java.net.http client for Qdrant's REST API. We talk to /collections
 * (list/get/create), /points (upsert), and /points/search.
 *
 * <p>Auth: if {@code ingest.qdrant.api-key} is non-empty, we send it as the
 * {@code api-key} header (Qdrant Cloud's convention). Local Qdrant typically
 * runs without auth.
 *
 * <p>HTTP/1.1 is pinned for consistency with the rest of the project — Qdrant
 * itself speaks HTTP/2 fine over the REST port, but pinning avoids surprises
 * if the deployment ever sits behind a uvicorn-ish proxy.
 */
@ApplicationScoped
public class QdrantClient {

    @ConfigProperty(name = "ingest.qdrant.url")
    String baseUrl;

    @ConfigProperty(name = "ingest.qdrant.api-key")
    String apiKey;

    @ConfigProperty(name = "ingest.qdrant.distance", defaultValue = "Cosine")
    String distance;

    @ConfigProperty(name = "ingest.qdrant.connect-timeout-seconds", defaultValue = "10")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "ingest.qdrant.request-timeout-seconds", defaultValue = "120")
    long requestTimeoutSeconds;

    @Inject
    ObjectMapper objectMapper;

    private HttpClient http;

    @PostConstruct
    void init() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    // ---- collections --------------------------------------------------------

    public List<CollectionSummary> listCollections() {
        HttpRequest req = builder("/collections").GET().build();
        CollectionsListResponse parsed = sendForJson(req, new TypeReference<CollectionsListResponse>() {
        });
        if (parsed == null || parsed.result == null || parsed.result.collections == null) {
            return List.of();
        }
        return parsed.result.collections;
    }

    /**
     * Returns metadata for {@code name} or null if the collection does not exist.
     */
    public CollectionInfo getCollection(String name) {
        HttpRequest req = builder("/collections/" + encode(name)).GET().build();
        HttpResponse<byte[]> resp = sendRaw(req);
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("Qdrant GET /collections/" + name + " returned HTTP "
                    + resp.statusCode() + ": " + new String(resp.body(), StandardCharsets.UTF_8));
        }
        CollectionInfoResponse parsed = readJson(resp.body(), new TypeReference<CollectionInfoResponse>() {
        });
        return parsed == null ? null : parsed.result;
    }

    /**
     * Delete a collection. Idempotent: 404 is treated as success (already gone),
     * any other non-2xx throws. Used by {@code drop_visual_index}.
     */
    public void deleteCollection(String name) {
        HttpRequest req = builder("/collections/" + encode(name))
                .DELETE()
                .build();
        HttpResponse<byte[]> resp = sendRaw(req);
        if (resp.statusCode() == 404) {
            return;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("Qdrant DELETE /collections/" + name + " returned HTTP "
                    + resp.statusCode() + ": " + new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    public void createCollection(String name, int dim) {
        Map<String, Object> vectors = Map.of("size", dim, "distance", distance);
        Map<String, Object> body = Map.of("vectors", vectors);
        HttpRequest req = builder("/collections/" + encode(name))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        sendExpectingSuccess(req);
    }

    public void ensureCollection(String name, int dim) {
        CollectionInfo existing = getCollection(name);
        if (existing == null) {
            createCollection(name, dim);
            return;
        }
        Integer existingDim = existing.dim();
        if (existingDim != null && existingDim != dim) {
            throw new IngestException("Collection '" + name + "' has dim=" + existingDim
                    + " but embeddings produced dim=" + dim
                    + ". Either delete the collection or change the embedding model.");
        }
    }

    // ---- multivector collections (ColPali / late interaction) ---------------

    /**
     * Configuration for one named vector in a multivector collection.
     *
     * @param size           per-token vector dimensionality (e.g. 128 for ColPali)
     * @param distance       similarity metric ({@code "Cosine"} / {@code "Dot"} / {@code "Euclid"})
     * @param comparator     multivector aggregation ({@code "max_sim"} is the only
     *                       value that makes sense for ColBERT-style late interaction)
     * @param hnswEnabled    when false, sets {@code hnsw_config.m=0} — disables ANN
     *                       indexing so this vector field is rerank-only (used for the
     *                       full-resolution {@code original} vectors in the
     *                       prefetch-then-rerank pattern)
     * @param binaryQuantize when true, adds {@code quantization_config.binary} with
     *                       {@code always_ram=true}; recovers most of the storage cost
     *                       of keeping the full-resolution vectors around
     */
    public record MultiVectorConfig(int size, String distance, String comparator,
                                     boolean hnswEnabled, boolean binaryQuantize) {

        /** Pooled-vector preset: ANN-indexed, no quantization. */
        public static MultiVectorConfig pooled(int size) {
            return new MultiVectorConfig(size, "Cosine", "max_sim", true, false);
        }

        /** Original-vector preset: HNSW disabled, binary quantization on. */
        public static MultiVectorConfig originalRerankOnly(int size) {
            return new MultiVectorConfig(size, "Cosine", "max_sim", false, true);
        }
    }

    /**
     * Create a multivector collection with named vector fields. Each named vector
     * is configured independently — typically one full-resolution {@code original}
     * (rerank-only) plus one or more pooled variants (ANN-indexed) for the
     * prefetch+rerank query pattern.
     */
    public void createMultivectorCollection(String name, Map<String, MultiVectorConfig> namedVectors) {
        if (namedVectors == null || namedVectors.isEmpty()) {
            throw new IngestException("createMultivectorCollection requires at least one named vector");
        }
        Map<String, Object> vectorsBody = new LinkedHashMap<>();
        for (Map.Entry<String, MultiVectorConfig> entry : namedVectors.entrySet()) {
            vectorsBody.put(entry.getKey(), renderMultiVectorConfig(entry.getValue()));
        }
        Map<String, Object> body = Map.of("vectors", vectorsBody);
        HttpRequest req = builder("/collections/" + encode(name))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        sendExpectingSuccess(req);
    }

    /**
     * Idempotently ensure a multivector collection exists with the given named
     * vectors. If a single-vector collection already exists at the same name
     * (clear shape mismatch), throws.
     */
    public void ensureMultivectorCollection(String name, Map<String, MultiVectorConfig> namedVectors) {
        CollectionInfo existing = getCollection(name);
        if (existing == null) {
            createMultivectorCollection(name, namedVectors);
            return;
        }
        if (existing.dim() != null) {
            throw new IngestException("Collection '" + name + "' is configured as a single-vector "
                    + "collection (dim=" + existing.dim() + ") but a multivector configuration was "
                    + "requested. Either delete the collection or use a different name.");
        }
        // Collection exists and is not single-vector. We don't deep-validate
        // the named-vector shape — Qdrant will reject upserts that don't match
        // its actual config, with a clear error.
    }

    private static Map<String, Object> renderMultiVectorConfig(MultiVectorConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", cfg.size());
        m.put("distance", cfg.distance() == null ? "Cosine" : cfg.distance());
        m.put("multivector_config",
                Map.of("comparator", cfg.comparator() == null ? "max_sim" : cfg.comparator()));
        if (!cfg.hnswEnabled()) {
            m.put("hnsw_config", Map.of("m", 0));
        }
        if (cfg.binaryQuantize()) {
            m.put("quantization_config",
                    Map.of("binary", Map.of("always_ram", true)));
        }
        return m;
    }

    // ---- multivector points -------------------------------------------------

    /**
     * One point in a multivector collection. {@code vectors} maps each named
     * vector field to its multi-vector value (a list of vectors, all sharing
     * the same dimensionality configured on the field).
     */
    public record MultiVectorPoint(String id,
                                    Map<String, float[][]> vectors,
                                    Map<String, Object> payload) {
    }

    /**
     * Upsert multivector points. Body shape:
     * <pre>
     * {
     *   "points": [
     *     {
     *       "id": "...",
     *       "vector": { "original": [[...], [...]], "pooled_rows": [[...], ...] },
     *       "payload": { ... }
     *     }
     *   ]
     * }
     * </pre>
     */
    public void upsertMultivectorPoints(String collection, List<MultiVectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rendered = new ArrayList<>(points.size());
        for (MultiVectorPoint p : points) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", p.id());
            point.put("vector", p.vectors() == null ? Map.of() : p.vectors());
            point.put("payload", p.payload() == null ? Map.of() : p.payload());
            rendered.add(point);
        }
        Map<String, Object> body = Map.of("points", rendered);
        HttpRequest req = builder("/collections/" + encode(collection) + "/points?wait=true")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        sendExpectingSuccess(req);
    }

    // ---- multistage query (prefetch + rerank) -------------------------------

    /**
     * A prefetch stage in a multistage query. Each stage runs against one named
     * vector field, returns its top-K candidates by ANN similarity, and the
     * union of all prefetched candidates becomes the input to the rerank step.
     */
    public record PrefetchSpec(String using, float[][] query, int limit) {
    }

    /**
     * Run a multistage prefetch + rerank query against a multivector collection.
     * Issues a single {@code POST /collections/{name}/points/query} with the
     * Qdrant 1.10+ unified query API.
     */
    public List<SearchHitRaw> queryMultistage(String collection,
                                              List<PrefetchSpec> prefetches,
                                              String rerankUsing,
                                              float[][] rerankQuery,
                                              int limit,
                                              Map<String, Object> filter) {
        if (rerankUsing == null || rerankUsing.isBlank()) {
            throw new IngestException("rerankUsing is required for multistage query");
        }
        if (rerankQuery == null || rerankQuery.length == 0) {
            throw new IngestException("rerankQuery vectors are required for multistage query");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (prefetches != null && !prefetches.isEmpty()) {
            List<Map<String, Object>> prefetchList = new ArrayList<>(prefetches.size());
            for (PrefetchSpec p : prefetches) {
                Map<String, Object> stage = new LinkedHashMap<>();
                stage.put("query", p.query());
                stage.put("using", p.using());
                stage.put("limit", p.limit());
                prefetchList.add(stage);
            }
            body.put("prefetch", prefetchList);
        }
        body.put("query", rerankQuery);
        body.put("using", rerankUsing);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", toQdrantFilter(filter));
        }

        HttpRequest req = builder("/collections/" + encode(collection) + "/points/query")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        QueryResponse parsed = sendForJson(req, new TypeReference<QueryResponse>() {
        });
        if (parsed == null || parsed.result == null || parsed.result.points == null) {
            return List.of();
        }
        List<SearchHitRaw> out = new ArrayList<>(parsed.result.points.size());
        for (RawHit h : parsed.result.points) {
            out.add(new SearchHitRaw(String.valueOf(h.id), h.score, h.payload));
        }
        return out;
    }

    // ---- points -------------------------------------------------------------

    public record Point(String id, float[] vector, Map<String, Object> payload) {
    }

    public void upsertPoints(String collection, List<Point> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rendered = new ArrayList<>(points.size());
        for (Point p : points) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("vector", p.vector());
            m.put("payload", p.payload() == null ? Map.of() : p.payload());
            rendered.add(m);
        }
        Map<String, Object> body = Map.of("points", rendered);
        HttpRequest req = builder("/collections/" + encode(collection) + "/points?wait=true")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        sendExpectingSuccess(req);
    }

    public record SearchHitRaw(String id, double score, Map<String, Object> payload) {
    }

    public List<SearchHitRaw> search(String collection, float[] vector, int topK,
                                     Map<String, Object> filter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", topK);
        body.put("with_payload", true);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", toQdrantFilter(filter));
        }
        HttpRequest req = builder("/collections/" + encode(collection) + "/points/search")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        SearchResponse parsed = sendForJson(req, new TypeReference<SearchResponse>() {
        });
        if (parsed == null || parsed.result == null) {
            return List.of();
        }
        List<SearchHitRaw> out = new ArrayList<>(parsed.result.size());
        for (RawHit h : parsed.result) {
            out.add(new SearchHitRaw(String.valueOf(h.id), h.score, h.payload));
        }
        return out;
    }

    /** Convert a flat {key: value} map into Qdrant's {must: [{key, match: {value}}]} shape. */
    static Map<String, Object> toQdrantFilter(Map<String, Object> filter) {
        List<Map<String, Object>> must = new ArrayList<>();
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            must.add(Map.of(
                    "key", e.getKey(),
                    "match", Map.of("value", e.getValue())));
        }
        return Map.of("must", must);
    }

    // ---- plumbing -----------------------------------------------------------

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("api-key", apiKey);
        }
        return b;
    }

    private <T> T sendForJson(HttpRequest req, TypeReference<T> type) {
        HttpResponse<byte[]> resp = sendRaw(req);
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("Qdrant " + req.method() + " " + req.uri()
                    + " returned HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        return readJson(resp.body(), type);
    }

    private void sendExpectingSuccess(HttpRequest req) {
        HttpResponse<byte[]> resp = sendRaw(req);
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("Qdrant " + req.method() + " " + req.uri()
                    + " returned HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    private HttpResponse<byte[]> sendRaw(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IngestException("I/O error calling Qdrant at " + req.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted calling Qdrant at " + req.uri(), e);
        }
    }

    private <T> T readJson(byte[] body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new IngestException("Failed to parse Qdrant response: "
                    + new String(body, StandardCharsets.UTF_8), e);
        }
    }

    private byte[] writeJson(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IngestException("Failed to serialize JSON for Qdrant", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    // ---- DTOs ---------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionSummary(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CollectionInfo {
        public long points_count;
        public long vectors_count;
        public Config config;

        public Integer dim() {
            if (config != null && config.params != null && config.params.vectors != null) {
                return config.params.vectors.size;
            }
            return null;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Config {
            public Params params;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Params {
            public VectorParams vectors;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class VectorParams {
            public Integer size;
            public String distance;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CollectionsListResponse {
        public CollectionsList result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CollectionsList {
        public List<CollectionSummary> collections;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CollectionInfoResponse {
        public CollectionInfo result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchResponse {
        public List<RawHit> result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawHit {
        public Object id;
        public double score;
        public Map<String, Object> payload;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class QueryResponse {
        public QueryResult result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class QueryResult {
        public List<RawHit> points;
    }
}
