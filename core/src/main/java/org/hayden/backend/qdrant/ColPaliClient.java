package org.hayden.backend.qdrant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.SidecarUnavailableException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    /**
     * Wire encoding requested for the page vectors: {@code json} (lists of
     * numbers), {@code f32b64} (base64 little-endian float32, bit-identical
     * to json, 2.3x smaller, one-pass decode) or {@code f16b64} (float16,
     * 4.6x smaller, exact for the model's bf16 outputs above 6.1e-5). A
     * sidecar that predates the field ignores it and answers json; the
     * parser accepts either form per field, so the two sides roll
     * independently. JSON parsing of a 12-page batch built ~5 million boxed
     * Doubles per worker and set the ingest JVM's heap ceiling (R530,
     * 2026-09-17).
     */
    @ConfigProperty(name = "ingest.colpali.wire-encoding", defaultValue = "f32b64")
    String wireEncoding;

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
        String encoding = (wireEncoding == null || wireEncoding.isBlank()) ? "json" : wireEncoding;
        EmbedPagesRequest body = new EmbedPagesRequest(items, true, true, encoding);

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
                    decodeVectors(dto.original, dto.dim, dto.encoding),
                    decodeVectors(dto.pooled_rows, dto.dim, dto.encoding),
                    decodeVectors(dto.pooled_cols, dto.dim, dto.encoding)));
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

    private static final Logger LOG = Logger.getLogger(ColPaliClient.class);

    /** Attempts per request. Embedding is a pure function of the request, so a retry is safe. */
    static final int SEND_ATTEMPTS = 2;
    static final long RETRY_DELAY_MS = 2_000;

    private <T> T sendForJson(HttpRequest req, TypeReference<T> type) {
        HttpResponse<byte[]> resp = sendRaw(req);
        int code = resp.statusCode();
        if (code / 100 != 2) {
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            String msg = "ColPali sidecar " + req.method() + " " + req.uri()
                    + " returned HTTP " + code + ": " + body;
            // 502/503/504 come from a proxy or balancer in front of the
            // sidecar(s) (no live upstream, restart window): an environment
            // condition, so the queue worker requeues instead of failing.
            if (code == 502 || code == 503 || code == 504) {
                throw new SidecarUnavailableException(msg);
            }
            throw new IngestException(msg);
        }
        return readJson(resp.body(), type);
    }

    /**
     * Send with one retry on I/O failure. Observed on the R530 pool
     * (2026-09-17): about 0.5% of embed POSTs failed with an IOException
     * while the balancer logged a 200 for every request it saw and no TCP
     * close crossed the wire — i.e. a client-side condition. A second
     * attempt on a fresh connection is cheap; if it also fails the job is
     * requeued as transient rather than lost, and the exception class and
     * message are logged so the cause can be seen.
     */
    private HttpResponse<byte[]> sendRaw(HttpRequest req) {
        IOException last = null;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                last = e;
                LOG.warnf("ColPali sidecar %s %s attempt %d/%d failed: %s: %s",
                        req.method(), req.uri(), attempt, SEND_ATTEMPTS,
                        e.getClass().getName(), e.getMessage());
                if (attempt < SEND_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IngestException("Interrupted calling ColPali sidecar at " + req.uri(), ie);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IngestException("Interrupted calling ColPali sidecar at " + req.uri(), e);
            }
        }
        throw new SidecarUnavailableException("I/O error calling ColPali sidecar at " + req.uri()
                + " after " + SEND_ATTEMPTS + " attempts: "
                + last.getClass().getSimpleName() + ": " + last.getMessage());
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

    /**
     * One vector array from the wire: a JSON array of arrays (encoding
     * {@code json}, or any sidecar that ignored the request field) or a
     * base64 string of row-major little-endian floats ({@code f32b64} /
     * {@code f16b64}, {@code dim} floats per row; "" is an empty array).
     */
    public static float[][] decodeVectors(JsonNode node, Integer dim, String encoding) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new float[0][];
        }
        if (node.isArray()) {
            return to2DFloat(node);
        }
        if (!node.isTextual()) {
            throw new IngestException("Unexpected vector encoding in sidecar response: " + node.getNodeType());
        }
        String b64 = node.asText();
        if (b64.isEmpty()) {
            return new float[0][];
        }
        if (dim == null || dim <= 0) {
            throw new IngestException("Sidecar sent base64 vectors without a positive dim");
        }
        boolean half = "f16b64".equals(encoding);
        int width = half ? 2 : 4;
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length % (width * dim) != 0) {
            throw new IngestException("Sidecar base64 vectors: " + raw.length
                    + " bytes is not a multiple of " + width + "*dim(" + dim + ")");
        }
        int rows = raw.length / (width * dim);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[rows][dim];
        for (int i = 0; i < rows; i++) {
            float[] row = out[i];
            if (half) {
                for (int j = 0; j < dim; j++) {
                    row[j] = Float.float16ToFloat(buf.getShort());
                }
            } else {
                buf.asFloatBuffer().get(row);
                buf.position(buf.position() + dim * 4);
            }
        }
        return out;
    }

    private static float[][] to2DFloat(JsonNode raw) {
        float[][] out = new float[raw.size()][];
        for (int i = 0; i < raw.size(); i++) {
            JsonNode row = raw.get(i);
            float[] arr = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                arr[j] = (float) row.get(j).doubleValue();
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

    record EmbedPagesRequest(List<PageItem> pages, boolean include_original, boolean include_pooled,
                             String encoding) {
        record PageItem(String page_id, String image_b64) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedPagesResponse {
        public List<PageEmbeddingDto> embeddings;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class PageEmbeddingDto {
            public String page_id;
            public JsonNode original;
            public JsonNode pooled_rows;
            public JsonNode pooled_cols;
            public Integer dim;        // present with base64 encodings
            public String encoding;    // echoed by the sidecar for base64 encodings
        }
    }

    record EmbedQueryRequest(String query) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedQueryResponse {
        public JsonNode vectors;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HealthResponse {
        public String status;
        public boolean ready;
    }
}
