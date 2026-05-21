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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * HTTP client for the ColPali sidecar. The sidecar is a separate Python service
 * (typically running ColQwen2 / ColPali / ColSmolVLM via HuggingFace Transformers)
 * that produces multi-vector embeddings for page images and queries.
 *
 * <p>The Java side stays model-agnostic via {@link #getInfo()} — the sidecar
 * advertises its model name, vector dim, batch size, and device. Switching
 * models or sidecar implementations (PyTorch, ONNX-INT8, llama.cpp) is a
 * deployment-time concern, not a code change here.
 *
 * <p>Wire shape (all JSON):
 * <pre>
 *   GET  /info        → { model_name, vector_dim, supports_pooled,
 *                         pooled_methods, max_batch_size, device }
 *   POST /embed_pages → request: { pages: [{page_id, image_b64}],
 *                                  include_original, include_pooled }
 *                       response: { embeddings: [{page_id, original,
 *                                                pooled_rows, pooled_cols}] }
 *   POST /embed_query → request: { query: "..." }
 *                       response: { vectors: [[...], ...] }
 *   GET  /healthz     → { status, ready }
 * </pre>
 *
 * <p>HTTP/1.1 is pinned for the same reason as elsewhere in the project —
 * uvicorn (which fronts most Python ML sidecars including the reference impl)
 * doesn't tolerate the cleartext h2c upgrade that {@code java.net.http} sends
 * by default.
 */
@ApplicationScoped
public class ColPaliClient {

    @ConfigProperty(name = "ingest.colpali.sidecar-url")
    String baseUrl;

    @ConfigProperty(name = "ingest.colpali.connect-timeout-seconds", defaultValue = "10")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "ingest.colpali.request-timeout-seconds", defaultValue = "300")
    long requestTimeoutSeconds;

    @ConfigProperty(name = "ingest.colpali.batch-size", defaultValue = "8")
    int batchSize;

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

    /** Sidecar self-report. Cached by callers if they want to avoid repeat lookups. */
    public SidecarInfo getInfo() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/info"))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendForJson(req, new TypeReference<SidecarInfo>() {
        });
    }

    /** Liveness check — returns true if the sidecar is reachable and reports ready. */
    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/healthz"))
                    .timeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = sendRaw(req);
            if (resp.statusCode() / 100 != 2) {
                return false;
            }
            HealthResponse parsed = readJson(resp.body(), new TypeReference<HealthResponse>() {
            });
            return parsed != null && parsed.ready;
        } catch (IngestException e) {
            return false;
        }
    }

    /**
     * Embed a list of page images. Pages are batched at
     * {@code ingest.colpali.batch-size} (default 8) — vision models are
     * memory-hungry and small batches keep GPU/CPU memory predictable.
     *
     * @param pages list of pages to embed; each carries a stable {@code pageId}
     *              (typically {@code <doc_id>:<page_number>}) and PNG bytes
     * @return the embedding triple (original / pooled_rows / pooled_cols) for
     *         each page, in input order
     */
    public List<PageEmbedding> embedPages(List<PageInput> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<PageEmbedding> out = new java.util.ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i += batchSize) {
            int end = Math.min(i + batchSize, pages.size());
            out.addAll(embedBatch(pages.subList(i, end)));
        }
        return out;
    }

    private List<PageEmbedding> embedBatch(List<PageInput> batch) {
        List<EmbedPagesRequest.PageItem> items = new java.util.ArrayList<>(batch.size());
        for (PageInput p : batch) {
            if (p.pngBytes() == null || p.pngBytes().length == 0) {
                throw new IngestException("Cannot embed empty PNG for page " + p.pageId());
            }
            items.add(new EmbedPagesRequest.PageItem(p.pageId(),
                    Base64.getEncoder().encodeToString(p.pngBytes())));
        }
        EmbedPagesRequest body = new EmbedPagesRequest(items, true, true);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/embed_pages"))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        EmbedPagesResponse parsed = sendForJson(req, new TypeReference<EmbedPagesResponse>() {
        });
        if (parsed == null || parsed.embeddings == null
                || parsed.embeddings.size() != batch.size()) {
            int got = parsed == null || parsed.embeddings == null ? 0 : parsed.embeddings.size();
            throw new IngestException("Sidecar returned " + got + " embeddings for "
                    + batch.size() + " pages");
        }
        List<PageEmbedding> out = new java.util.ArrayList<>(batch.size());
        for (int i = 0; i < parsed.embeddings.size(); i++) {
            EmbedPagesResponse.PageEmbeddingDto dto = parsed.embeddings.get(i);
            out.add(new PageEmbedding(
                    dto.page_id,
                    to2DFloat(dto.original),
                    to2DFloat(dto.pooled_rows),
                    to2DFloat(dto.pooled_cols)));
        }
        return out;
    }

    /**
     * Embed a query string. The sidecar runs the model's text encoder and
     * returns the query's multi-vector representation (typically 10-30 tokens
     * × the same vector_dim as page embeddings).
     */
    public float[][] embedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IngestException("query is required");
        }
        EmbedQueryRequest body = new EmbedQueryRequest(query);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/embed_query"))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();
        EmbedQueryResponse parsed = sendForJson(req, new TypeReference<EmbedQueryResponse>() {
        });
        if (parsed == null || parsed.vectors == null || parsed.vectors.isEmpty()) {
            throw new IngestException("Sidecar returned no query vectors");
        }
        return to2DFloat(parsed.vectors);
    }

    // ---- plumbing -----------------------------------------------------------

    private <T> T sendForJson(HttpRequest req, TypeReference<T> type) {
        HttpResponse<byte[]> resp = sendRaw(req);
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("ColPali sidecar " + req.method() + " " + req.uri()
                    + " returned HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        return readJson(resp.body(), type);
    }

    private HttpResponse<byte[]> sendRaw(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IngestException("I/O error calling ColPali sidecar at " + req.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted calling ColPali sidecar at " + req.uri(), e);
        }
    }

    private <T> T readJson(byte[] body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new IngestException("Failed to parse ColPali sidecar response: "
                    + new String(body, StandardCharsets.UTF_8), e);
        }
    }

    private byte[] writeJson(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IngestException("Failed to serialize JSON for ColPali sidecar", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static float[][] to2DFloat(List<List<Double>> raw) {
        if (raw == null) {
            return new float[0][];
        }
        float[][] out = new float[raw.size()][];
        for (int i = 0; i < raw.size(); i++) {
            List<Double> row = raw.get(i);
            float[] arr = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                arr[j] = row.get(j).floatValue();
            }
            out[i] = arr;
        }
        return out;
    }

    // ---- public types -------------------------------------------------------

    /** Input to {@link #embedPages(List)} — pair of page id and PNG bytes. */
    public record PageInput(String pageId, byte[] pngBytes) {
    }

    /** Output of {@link #embedPages(List)} — the three named vectors per page. */
    public record PageEmbedding(String pageId,
                                 float[][] original,
                                 float[][] pooledRows,
                                 float[][] pooledCols) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SidecarInfo {
        public String model_name;
        public Integer vector_dim;
        public Boolean supports_pooled;
        public List<String> pooled_methods;
        public Integer max_batch_size;
        public String device;
    }

    // ---- DTOs (wire shapes) -------------------------------------------------

    record EmbedPagesRequest(List<PageItem> pages, boolean include_original, boolean include_pooled) {
        record PageItem(String page_id, String image_b64) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedPagesResponse {
        public List<PageEmbeddingDto> embeddings;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class PageEmbeddingDto {
            public String page_id;
            public List<List<Double>> original;
            public List<List<Double>> pooled_rows;
            public List<List<Double>> pooled_cols;
        }
    }

    record EmbedQueryRequest(String query) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedQueryResponse {
        public List<List<Double>> vectors;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HealthResponse {
        public String status;
        public boolean ready;
    }
}
