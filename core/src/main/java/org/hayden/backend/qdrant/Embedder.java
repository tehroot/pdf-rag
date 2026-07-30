package org.hayden.backend.qdrant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible embeddings client. The project default backend is
 * llama.cpp's {@code llama-server} (started with {@code --embeddings}), which
 * exposes {@code POST /v1/embeddings} accepting {@code {model, input: [...]}}
 * and returning {@code {data: [{embedding: [...]}]}}. The same wire shape
 * works for vLLM, vanilla OpenAI, Together, LM Studio, etc.
 *
 * <p>HTTP/1.1 is pinned because llama-server speaks HTTP/1.1 only on the
 * plaintext port, and any uvicorn-fronted alternative (vLLM, Open WebUI)
 * rejects the cleartext h2c upgrade trio that {@code java.net.http} sends by
 * default. See the Open WebUI gotcha for the long version.
 */
@ApplicationScoped
public class Embedder {

    private static final Logger LOG = Logger.getLogger(Embedder.class);

    @ConfigProperty(name = "ingest.embed.base-url")
    String baseUrl;

    @ConfigProperty(name = "ingest.embed.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "ingest.embed.model")
    String model;

    @ConfigProperty(name = "ingest.embed.batch-size", defaultValue = "64")
    int batchSize;

    @ConfigProperty(name = "ingest.embed.connect-timeout-seconds", defaultValue = "10")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "ingest.embed.request-timeout-seconds", defaultValue = "120")
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

    public float[] embedOne(String input) {
        List<float[]> out = embed(List.of(input));
        return out.get(0);
    }

    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, inputs.size());
            out.addAll(embedBatch(inputs.subList(i, end)));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        Attempt attempt = requestEmbeddings(batch);
        if (attempt.vectors != null) {
            return attempt.vectors;
        }
        // One input in the batch busted the model's token cap (llama-server:
        // "input (N tokens) is too large to process"). The server doesn't say
        // WHICH input, so isolate per input and truncate only the offenders.
        LOG.warnf("Embedding batch of %d rejected as too large; isolating per input (%s)",
                batch.size(), attempt.error);
        List<float[]> out = new ArrayList<>(batch.size());
        for (String input : batch) {
            out.add(embedWithTruncation(input));
        }
        return out;
    }

    /**
     * Embed one input, progressively truncating on token-cap overflow. Dense
     * technical text (part tables, OCR debris) can tokenize under 1.4 chars per
     * token, so a chunk sized comfortably in characters can still exceed the
     * encoder's hard cap (512 tokens for bge-*). Only the embedding input is
     * clipped — the stored chunk text is untouched, and the encoder couldn't
     * attend past its cap anyway.
     */
    private float[] embedWithTruncation(String input) {
        String candidate = input;
        while (true) {
            Attempt attempt = requestEmbeddings(List.of(candidate));
            if (attempt.vectors != null) {
                if (candidate.length() < input.length()) {
                    LOG.warnf("Embedded truncated input (%d of %d chars): \"%.60s…\"",
                            candidate.length(), input.length(), input);
                }
                return attempt.vectors.get(0);
            }
            if (candidate.length() <= MIN_TRUNCATED_CHARS) {
                throw new IngestException("Input still rejected as too large at "
                        + candidate.length() + " chars: " + attempt.error);
            }
            candidate = candidate.substring(0, (int) (candidate.length() * TRUNCATE_FACTOR));
        }
    }

    /** Result of one embeddings call: vectors on success, or the server's
     *  error text when it rejected an input as over the token cap. Any other
     *  failure throws from {@link #requestEmbeddings}. */
    private record Attempt(List<float[]> vectors, String error) {
    }

    private static final double TRUNCATE_FACTOR = 0.75;
    private static final int MIN_TRUNCATED_CHARS = 100;

    private Attempt requestEmbeddings(List<String> batch) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new EmbedRequest(model, batch));
        } catch (IOException e) {
            throw new IngestException("Failed to serialize embedding request", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "/embeddings"))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<byte[]> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IngestException("I/O error calling embeddings endpoint at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted calling embeddings endpoint", e);
        }
        if (resp.statusCode() / 100 != 2) {
            String respBody = new String(resp.body(), StandardCharsets.UTF_8);
            if (isInputTooLarge(respBody)) {
                return new Attempt(null, respBody);
            }
            throw new IngestException("Embeddings endpoint returned HTTP " + resp.statusCode()
                    + ": " + respBody);
        }

        EmbedResponse parsed;
        try {
            parsed = objectMapper.readValue(resp.body(), new TypeReference<EmbedResponse>() {
            });
        } catch (IOException e) {
            throw new IngestException("Failed to parse embeddings response", e);
        }
        if (parsed.data == null || parsed.data.size() != batch.size()) {
            throw new IngestException("Embeddings response returned "
                    + (parsed.data == null ? 0 : parsed.data.size())
                    + " vectors for " + batch.size() + " inputs");
        }
        List<float[]> out = new ArrayList<>(batch.size());
        for (EmbedResponse.Item item : parsed.data) {
            out.add(toFloatArray(item.embedding));
        }
        return new Attempt(out, null);
    }

    /** llama-server phrasing plus the OpenAI-style equivalent. */
    private static boolean isInputTooLarge(String errorBody) {
        return errorBody != null
                && (errorBody.contains("too large to process")
                    || errorBody.contains("maximum context length"));
    }

    public String model() {
        return model;
    }

    private static float[] toFloatArray(List<Double> in) {
        if (in == null) {
            throw new IngestException("Embedding response had a null vector");
        }
        float[] out = new float[in.size()];
        for (int i = 0; i < in.size(); i++) {
            out[i] = in.get(i).floatValue();
        }
        return out;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    record EmbedRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedResponse {
        public List<Item> data;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Item {
            public List<Double> embedding;
        }
    }
}
