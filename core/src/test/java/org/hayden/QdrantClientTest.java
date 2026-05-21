package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.hayden.backend.qdrant.QdrantClient;
import org.hayden.ingest.IngestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantClientTest {

    private WireMockServer server;
    private QdrantClient client;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = newClient(server.baseUrl(), "Cosine", "");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void listCollections_parsesNames() {
        server.stubFor(get(urlEqualTo("/collections"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("""
                                {"result":{"collections":[
                                  {"name":"docs"},
                                  {"name":"reports"}
                                ]}}""")));

        List<QdrantClient.CollectionSummary> out = client.listCollections();

        assertThat(out).extracting(QdrantClient.CollectionSummary::name)
                .containsExactly("docs", "reports");
    }

    @Test
    void getCollection_returnsNullOn404() {
        server.stubFor(get(urlEqualTo("/collections/missing"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));

        assertThat(client.getCollection("missing")).isNull();
    }

    @Test
    void getCollection_parsesDimAndCounts() {
        server.stubFor(get(urlEqualTo("/collections/docs"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("""
                                {"result":{
                                  "points_count": 42,
                                  "vectors_count": 42,
                                  "config":{"params":{"vectors":{"size":384,"distance":"Cosine"}}}
                                }}""")));

        QdrantClient.CollectionInfo info = client.getCollection("docs");
        assertThat(info).isNotNull();
        assertThat(info.dim()).isEqualTo(384);
        assertThat(info.vectors_count).isEqualTo(42);
    }

    @Test
    void ensureCollection_createsWhenMissing() {
        server.stubFor(get(urlEqualTo("/collections/new"))
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(put(urlEqualTo("/collections/new"))
                .withRequestBody(matchingJsonPath("$.vectors.size", equalTo("8")))
                .withRequestBody(matchingJsonPath("$.vectors.distance", equalTo("Cosine")))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));

        client.ensureCollection("new", 8);

        server.verify(putRequestedFor(urlEqualTo("/collections/new")));
    }

    @Test
    void ensureCollection_rejectsDimMismatch() {
        server.stubFor(get(urlEqualTo("/collections/docs"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "config":{"params":{"vectors":{"size":768,"distance":"Cosine"}}}
                        }}""")));

        assertThatThrownBy(() -> client.ensureCollection("docs", 384))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("dim=768");
    }

    @Test
    void upsertPoints_sendsExpectedBody() {
        server.stubFor(put(urlPathEqualTo("/collections/docs/points"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

        client.upsertPoints("docs", List.of(
                new QdrantClient.Point("11111111-1111-1111-1111-111111111111",
                        new float[]{0.1f, 0.2f}, Map.of("text", "chunk-0"))));

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).contains("11111111-1111-1111-1111-111111111111");
        assertThat(body).contains("\"text\":\"chunk-0\"");
        assertThat(captured.getUrl()).contains("wait=true");
    }

    @Test
    void search_passesFilterAndParsesHits() {
        server.stubFor(post(urlEqualTo("/collections/docs/points/search"))
                .withRequestBody(equalToJson("""
                        {"vector":[0.1,0.2],
                         "limit":3,
                         "with_payload":true,
                         "filter":{"must":[{"key":"source","match":{"value":"a.pdf"}}]}}"""))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":[
                          {"id":"id-1","score":0.91,"payload":{"text":"hello"}},
                          {"id":"id-2","score":0.71,"payload":{"text":"world"}}
                        ]}""")));

        List<QdrantClient.SearchHitRaw> hits = client.search("docs",
                new float[]{0.1f, 0.2f}, 3,
                Map.of("source", "a.pdf"));

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).id()).isEqualTo("id-1");
        assertThat(hits.get(0).score()).isCloseTo(0.91, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(0).payload()).containsEntry("text", "hello");
    }

    // ---- multivector --------------------------------------------------------

    @Test
    void createMultivectorCollection_sendsExpectedBody() {
        server.stubFor(put(urlEqualTo("/collections/pages"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));

        Map<String, QdrantClient.MultiVectorConfig> namedVectors = new java.util.LinkedHashMap<>();
        namedVectors.put("original", QdrantClient.MultiVectorConfig.originalRerankOnly(128));
        namedVectors.put("pooled_rows", QdrantClient.MultiVectorConfig.pooled(128));
        namedVectors.put("pooled_cols", QdrantClient.MultiVectorConfig.pooled(128));
        client.createMultivectorCollection("pages", namedVectors);

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).contains("\"original\"");
        assertThat(body).contains("\"pooled_rows\"");
        assertThat(body).contains("\"pooled_cols\"");
        assertThat(body).contains("\"max_sim\"");
        assertThat(body).contains("\"size\":128");
        // original is rerank-only → hnsw disabled and binary quantization on.
        assertThat(body).contains("\"hnsw_config\":{\"m\":0}");
        assertThat(body).contains("\"quantization_config\"");
        assertThat(body).contains("\"binary\"");
        assertThat(body).contains("\"always_ram\":true");
    }

    @Test
    void ensureMultivectorCollection_createsWhenMissing() {
        server.stubFor(get(urlEqualTo("/collections/pages"))
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(put(urlEqualTo("/collections/pages"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));

        client.ensureMultivectorCollection("pages",
                Map.of("original", QdrantClient.MultiVectorConfig.originalRerankOnly(128),
                       "pooled_rows", QdrantClient.MultiVectorConfig.pooled(128)));

        server.verify(putRequestedFor(urlEqualTo("/collections/pages")));
    }

    @Test
    void ensureMultivectorCollection_rejectsExistingSingleVectorCollection() {
        // A single-vector collection at the same name → shape mismatch error.
        server.stubFor(get(urlEqualTo("/collections/pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "config":{"params":{"vectors":{"size":1024,"distance":"Cosine"}}}
                        }}""")));

        assertThatThrownBy(() -> client.ensureMultivectorCollection("pages",
                Map.of("original", QdrantClient.MultiVectorConfig.originalRerankOnly(128))))
                .isInstanceOf(org.hayden.ingest.IngestException.class)
                .hasMessageContaining("single-vector");
    }

    @Test
    void ensureMultivectorCollection_existingMultivector_noOps() {
        // Multivector collection: CollectionInfo.dim() returns null because the
        // nested "vectors" field is a map, not a single object Jackson can parse.
        server.stubFor(get(urlEqualTo("/collections/pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "config":{"params":{}}
                        }}""")));

        client.ensureMultivectorCollection("pages",
                Map.of("original", QdrantClient.MultiVectorConfig.originalRerankOnly(128)));

        // Should NOT have called PUT to create.
        server.verify(0, putRequestedFor(urlEqualTo("/collections/pages")));
    }

    @Test
    void upsertMultivectorPoints_sendsNamedVectorBody() {
        server.stubFor(put(urlPathEqualTo("/collections/pages/points"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

        QdrantClient.MultiVectorPoint point = new QdrantClient.MultiVectorPoint(
                "11111111-1111-1111-1111-111111111111",
                Map.of(
                        "original", new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}},
                        "pooled_rows", new float[][]{{0.5f, 0.6f}}
                ),
                Map.of("doc_id", "d-1", "page_number", 7));
        client.upsertMultivectorPoints("pages", List.of(point));

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).contains("11111111-1111-1111-1111-111111111111");
        assertThat(body).contains("\"original\":[[0.1,0.2],[0.3,0.4]]");
        assertThat(body).contains("\"pooled_rows\":[[0.5,0.6]]");
        assertThat(body).contains("\"doc_id\":\"d-1\"");
        assertThat(captured.getUrl()).contains("wait=true");
    }

    @Test
    void queryMultistage_sendsExpectedShape_andParsesHits() {
        server.stubFor(post(urlEqualTo("/collections/pages/points/query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"points":[
                          {"id":"id-1","score":0.93,"payload":{"doc_id":"d-1","page_number":3}},
                          {"id":"id-2","score":0.81,"payload":{"doc_id":"d-2","page_number":1}}
                        ]}}""")));

        float[][] queryVecs = new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}, {0.5f, 0.6f}};
        List<QdrantClient.PrefetchSpec> prefetches = List.of(
                new QdrantClient.PrefetchSpec("pooled_rows", queryVecs, 100),
                new QdrantClient.PrefetchSpec("pooled_cols", queryVecs, 100));
        List<QdrantClient.SearchHitRaw> hits = client.queryMultistage(
                "pages", prefetches, "original", queryVecs, 10,
                Map.of("doc_id", "d-1"));

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).id()).isEqualTo("id-1");
        assertThat(hits.get(0).score()).isCloseTo(0.93, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(0).payload()).containsEntry("doc_id", "d-1");

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).contains("\"prefetch\"");
        assertThat(body).contains("\"using\":\"pooled_rows\"");
        assertThat(body).contains("\"using\":\"pooled_cols\"");
        assertThat(body).contains("\"using\":\"original\"");
        assertThat(body).contains("\"limit\":100");
        assertThat(body).contains("\"limit\":10");
        assertThat(body).contains("\"with_payload\":true");
        assertThat(body).contains("\"filter\"");
        assertThat(body).contains("\"must\"");
    }

    @Test
    void queryMultistage_emptyPrefetch_omitsField() {
        server.stubFor(post(urlEqualTo("/collections/pages/points/query"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{\"points\":[]}}")));

        client.queryMultistage("pages", List.of(), "original",
                new float[][]{{0.1f, 0.2f}}, 5, null);

        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        assertThat(body).doesNotContain("\"prefetch\"");
        assertThat(body).doesNotContain("\"filter\"");
    }

    @Test
    void queryMultistage_invalidParams_throws() {
        assertThatThrownBy(() -> client.queryMultistage("pages", List.of(),
                null, new float[][]{{0.1f}}, 5, null))
                .isInstanceOf(org.hayden.ingest.IngestException.class)
                .hasMessageContaining("rerankUsing");
        assertThatThrownBy(() -> client.queryMultistage("pages", List.of(),
                "original", new float[0][], 5, null))
                .isInstanceOf(org.hayden.ingest.IngestException.class)
                .hasMessageContaining("rerankQuery");
    }

    @Test
    void multiVectorConfig_presets_haveExpectedShape() {
        QdrantClient.MultiVectorConfig pooled = QdrantClient.MultiVectorConfig.pooled(128);
        assertThat(pooled.size()).isEqualTo(128);
        assertThat(pooled.comparator()).isEqualTo("max_sim");
        assertThat(pooled.hnswEnabled()).isTrue();
        assertThat(pooled.binaryQuantize()).isFalse();

        QdrantClient.MultiVectorConfig orig = QdrantClient.MultiVectorConfig.originalRerankOnly(128);
        assertThat(orig.hnswEnabled()).isFalse();
        assertThat(orig.binaryQuantize()).isTrue();
    }

    @Test
    void sendsApiKeyHeader_whenConfigured() throws Exception {
        QdrantClient withKey = newClient(server.baseUrl(), "Cosine", "secret-key");
        server.stubFor(get(urlEqualTo("/collections"))
                .withHeader("api-key", equalTo("secret-key"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{\"collections\":[]}}")));

        withKey.listCollections();
    }

    static QdrantClient newClient(String baseUrl, String distance, String apiKey) throws Exception {
        QdrantClient c = new QdrantClient();
        setField(c, "baseUrl", baseUrl);
        setField(c, "apiKey", apiKey);
        setField(c, "distance", distance);
        setField(c, "connectTimeoutSeconds", 5L);
        setField(c, "requestTimeoutSeconds", 10L);
        setField(c, "objectMapper", new ObjectMapper());
        var init = QdrantClient.class.getDeclaredMethod("init");
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
