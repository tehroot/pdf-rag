package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.hayden.backend.qdrant.ColPaliClient;
import org.hayden.ingest.IngestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColPaliClientTest {

    private WireMockServer server;
    private ColPaliClient client;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = newClient(server.baseUrl(), 4);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void getInfo_parsesSidecarSelfReport() {
        server.stubFor(get(urlEqualTo("/info"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "model_name": "vidore/colqwen2-v1.0",
                          "vector_dim": 128,
                          "supports_pooled": true,
                          "pooled_methods": ["rows", "cols"],
                          "max_batch_size": 16,
                          "device": "cuda:0"
                        }""")));

        ColPaliClient.SidecarInfo info = client.getInfo();

        assertThat(info.model_name).isEqualTo("vidore/colqwen2-v1.0");
        assertThat(info.vector_dim).isEqualTo(128);
        assertThat(info.supports_pooled).isTrue();
        assertThat(info.pooled_methods).containsExactly("rows", "cols");
        assertThat(info.max_batch_size).isEqualTo(16);
        assertThat(info.device).isEqualTo("cuda:0");
    }

    @Test
    void healthCheck_ready_returnsTrue() {
        server.stubFor(get(urlEqualTo("/healthz"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"status\":\"ok\",\"ready\":true}")));
        assertThat(client.isHealthy()).isTrue();
    }

    @Test
    void healthCheck_notReady_returnsFalse() {
        server.stubFor(get(urlEqualTo("/healthz"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"status\":\"loading\",\"ready\":false}")));
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void healthCheck_serverDown_returnsFalse() {
        server.stop();
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void healthCheck_5xx_returnsFalse() {
        server.stubFor(get(urlEqualTo("/healthz"))
                .willReturn(aResponse().withStatus(503)));
        assertThat(client.isHealthy()).isFalse();
    }

    @Test
    void embedPages_singleBatch_sendsBase64AndParsesResponse() {
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "embeddings": [
                            {
                              "page_id": "doc-1:1",
                              "original":     [[0.1, 0.2], [0.3, 0.4]],
                              "pooled_rows":  [[0.5, 0.6]],
                              "pooled_cols":  [[0.7, 0.8]]
                            }
                          ]
                        }""")));

        List<ColPaliClient.PageEmbedding> out = client.embedPages(List.of(
                new ColPaliClient.PageInput("doc-1:1", "fake-png".getBytes())));

        assertThat(out).hasSize(1);
        ColPaliClient.PageEmbedding pe = out.get(0);
        assertThat(pe.pageId()).isEqualTo("doc-1:1");
        assertThat(pe.original()).hasNumberOfRows(2);
        assertThat(pe.original()[0]).containsExactly(0.1f, 0.2f);
        assertThat(pe.original()[1]).containsExactly(0.3f, 0.4f);
        assertThat(pe.pooledRows()[0]).containsExactly(0.5f, 0.6f);
        assertThat(pe.pooledCols()[0]).containsExactly(0.7f, 0.8f);

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).contains("\"page_id\":\"doc-1:1\"");
        // Base64 of "fake-png" = "ZmFrZS1wbmc="
        assertThat(body).contains("\"image_b64\":\"ZmFrZS1wbmc=\"");
        assertThat(body).contains("\"include_original\":true");
        assertThat(body).contains("\"include_pooled\":true");
    }

    @Test
    void embedPages_splitsByBatchSize() {
        // batchSize=4 (newClient param); ask for 9 pages → expect 3 batched POSTs (4 + 4 + 1).
        // Each stub returns N embeddings to match its batch.
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .withRequestBody(matchingJsonPath("$.pages.length()", equalTo("4")))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"embeddings":[
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]},
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]},
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]},
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]}
                        ]}""")));
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .withRequestBody(matchingJsonPath("$.pages.length()", equalTo("1")))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"embeddings":[
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]}
                        ]}""")));

        List<ColPaliClient.PageInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            inputs.add(new ColPaliClient.PageInput("p:" + i, ("img-" + i).getBytes()));
        }

        List<ColPaliClient.PageEmbedding> out = client.embedPages(inputs);

        assertThat(out).hasSize(9);
        server.verify(3, postRequestedFor(urlEqualTo("/embed_pages")));
    }

    @Test
    void embedPages_empty_returnsEmptyWithoutCall() {
        List<ColPaliClient.PageEmbedding> out = client.embedPages(List.of());
        assertThat(out).isEmpty();
        server.verify(0, postRequestedFor(urlEqualTo("/embed_pages")));
    }

    @Test
    void embedPages_sizeMismatch_throws() {
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"embeddings":[
                          {"page_id":"p","original":[[0.1]],"pooled_rows":[[0.1]],"pooled_cols":[[0.1]]}
                        ]}""")));

        List<ColPaliClient.PageInput> inputs = List.of(
                new ColPaliClient.PageInput("p:1", "a".getBytes()),
                new ColPaliClient.PageInput("p:2", "b".getBytes()));

        assertThatThrownBy(() -> client.embedPages(inputs))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("1 embeddings for 2 pages");
    }

    @Test
    void embedPages_emptyPng_throws() {
        assertThatThrownBy(() -> client.embedPages(List.of(
                new ColPaliClient.PageInput("p:1", new byte[0]))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("empty PNG");
    }

    @Test
    void embedPages_non2xx_throws() {
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .willReturn(aResponse().withStatus(503).withBody("model loading")));

        assertThatThrownBy(() -> client.embedPages(List.of(
                new ColPaliClient.PageInput("p:1", "x".getBytes()))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("503");
    }

    @Test
    void embedQuery_sendsQueryAndParsesVectors() {
        server.stubFor(post(urlEqualTo("/embed_query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"vectors":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}""")));

        float[][] vectors = client.embedQuery("rate limiting strategy");

        assertThat(vectors).hasNumberOfRows(2);
        assertThat(vectors[0]).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors[1]).containsExactly(0.4f, 0.5f, 0.6f);

        var captured = server.getAllServeEvents().get(0).getRequest();
        assertThat(new String(captured.getBody())).contains("\"rate limiting strategy\"");
    }

    @Test
    void embedQuery_blank_throws() {
        assertThatThrownBy(() -> client.embedQuery(null))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> client.embedQuery("  "))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void embedQuery_emptyVectors_throws() {
        server.stubFor(post(urlEqualTo("/embed_query"))
                .willReturn(aResponse().withStatus(200).withBody("{\"vectors\":[]}")));

        assertThatThrownBy(() -> client.embedQuery("anything"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("no query vectors");
    }

    private static ColPaliClient newClient(String baseUrl, int batchSize) throws Exception {
        ColPaliClient c = new ColPaliClient();
        setField(c, "baseUrl", baseUrl);
        setField(c, "connectTimeoutSeconds", 5L);
        setField(c, "requestTimeoutSeconds", 10L);
        setField(c, "batchSize", batchSize);
        setField(c, "objectMapper", new ObjectMapper());
        var init = ColPaliClient.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(c);
        return c;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
