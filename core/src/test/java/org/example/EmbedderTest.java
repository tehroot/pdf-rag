package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.example.backend.qdrant.Embedder;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbedderTest {

    private WireMockServer server;
    private Embedder embedder;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        embedder = newEmbedder(server.baseUrl() + "/v1", "bge-large", 3, "test-key");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void embed_singleBatch_returnsVectorsInOrder() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("bge-large")))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":[
                                  {"embedding":[1.0, 2.0, 3.0]},
                                  {"embedding":[4.0, 5.0, 6.0]}
                                ]}""")));

        List<float[]> out = embedder.embed(List.of("alpha", "beta"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsExactly(1.0f, 2.0f, 3.0f);
        assertThat(out.get(1)).containsExactly(4.0f, 5.0f, 6.0f);
    }

    @Test
    void embed_splitsBatches_byBatchSize() {
        // batchSize=3; 6 inputs → exactly 2 batches of 3, both matching the same stub.
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"embedding\":[0.1]},{\"embedding\":[0.2]},{\"embedding\":[0.3]}]}")));

        List<float[]> out = embedder.embed(List.of("a", "b", "c", "d", "e", "f"));

        assertThat(out).hasSize(6);
        server.verify(2, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void embed_emptyList_returnsEmpty_withoutCallingHttp() {
        List<float[]> out = embedder.embed(List.of());
        assertThat(out).isEmpty();
        server.verify(0, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void omitsAuthorizationHeader_whenApiKeyBlank() throws Exception {
        Embedder anon = newEmbedder(server.baseUrl() + "/v1", "m", 4, "");

        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"embedding\":[0.9]}]}")));

        anon.embed(List.of("hello"));

        var captured = server.getAllServeEvents().get(0).getRequest();
        assertThat(captured.getHeader("Authorization")).isNull();
    }

    @Test
    void non2xx_throwsIngestException() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(503).withBody("backend overloaded")));

        assertThatThrownBy(() -> embedder.embed(List.of("a")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("503");
    }

    @Test
    void mismatchedResultSize_throws() {
        server.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"embedding\":[1.0]}]}")));

        assertThatThrownBy(() -> embedder.embed(List.of("a", "b")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("vectors for");
    }

    private static Embedder newEmbedder(String baseUrl, String model, int batchSize, String apiKey)
            throws Exception {
        Embedder e = new Embedder();
        setField(e, "baseUrl", baseUrl);
        setField(e, "apiKey", apiKey);
        setField(e, "model", model);
        setField(e, "batchSize", batchSize);
        setField(e, "connectTimeoutSeconds", 5L);
        setField(e, "requestTimeoutSeconds", 10L);
        setField(e, "objectMapper", new ObjectMapper());
        var init = Embedder.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(e);
        return e;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
