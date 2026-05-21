package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.example.backend.qdrant.ColPaliClient;
import org.example.backend.qdrant.ColPaliPipeline;
import org.example.backend.qdrant.FilesystemPageImageStore;
import org.example.backend.qdrant.PageHit;
import org.example.backend.qdrant.PageRasterizer;
import org.example.backend.qdrant.QdrantClient;
import org.example.backend.qdrant.TextLayerProbe;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestRequest;
import org.example.ingest.IngestRequest.SourceType;
import org.example.ingest.SearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class ColPaliPipelineTest {

    private WireMockServer server;
    private Path tmpRoot;
    private ColPaliPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        tmpRoot = Files.createTempDirectory("colpali-pipeline-");
        pipeline = newPipeline(server.baseUrl(), tmpRoot.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop();
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            try (Stream<Path> walk = Files.walk(tmpRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void isEnabledFor_falseWhenPagesCollectionMissing() {
        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(404)));
        assertThat(pipeline.isEnabledFor("docs")).isFalse();
    }

    @Test
    void isEnabledFor_trueWhenPagesCollectionExists() {
        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"config":{"params":{}}}}""")));
        assertThat(pipeline.isEnabledFor("docs")).isTrue();
    }

    @Test
    void ingestPages_endToEnd_createsCollectionAndUpserts() throws Exception {
        // Sidecar stubs.
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"embeddings":[
                          {"page_id":"d-1:1","original":[[0.1,0.2]],"pooled_rows":[[0.1,0.2]],"pooled_cols":[[0.1,0.2]]},
                          {"page_id":"d-1:2","original":[[0.1,0.2]],"pooled_rows":[[0.1,0.2]],"pooled_cols":[[0.1,0.2]]}
                        ]}""")));
        server.stubFor(get(urlEqualTo("/info"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"model_name":"vidore/colqwen2-v1.0","vector_dim":2,
                         "supports_pooled":true,"max_batch_size":8,"device":"cpu"}""")));

        // Qdrant: pages collection doesn't exist yet → create, then upsert.
        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(put(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));
        server.stubFor(put(urlPathEqualTo("/collections/docs_pages/points"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

        FetchedFile pdf = pdfFixture(2);
        IngestRequest req = new IngestRequest(SourceType.PATH, "/tmp/test.pdf", null,
                "docs", null, 0L, "qdrant", Map.of("project", "alpha"));

        ColPaliPipeline.PagesIngestResult result =
                pipeline.ingestPages(req, pdf, "d-1");

        assertThat(result.collection()).isEqualTo("docs_pages");
        assertThat(result.docId()).isEqualTo("d-1");
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.vectorDim()).isEqualTo(2);

        // Multivector collection was created.
        server.verify(putRequestedFor(urlEqualTo("/collections/docs_pages")));
        // Page points were upserted (one PUT with both pages).
        server.verify(putRequestedFor(urlPathEqualTo("/collections/docs_pages/points")));

        // Page PNGs landed in the filesystem store.
        Path p1 = tmpRoot.resolve("docs/d-1/000001.png");
        Path p2 = tmpRoot.resolve("docs/d-1/000002.png");
        assertThat(Files.exists(p1)).isTrue();
        assertThat(Files.exists(p2)).isTrue();
    }

    @Test
    void ingestPages_existingCollection_skipsCreate() throws Exception {
        server.stubFor(post(urlEqualTo("/embed_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"embeddings":[
                          {"page_id":"d-2:1","original":[[0.1,0.2]],"pooled_rows":[[0.1,0.2]],"pooled_cols":[[0.1,0.2]]}
                        ]}""")));
        server.stubFor(get(urlEqualTo("/info"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"model_name":"m","vector_dim":2,"supports_pooled":true,"max_batch_size":8,"device":"cpu"}""")));
        // Existing multivector collection: getCollection returns a 200 with dim()=null
        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"config":{"params":{}}}}""")));
        server.stubFor(put(urlPathEqualTo("/collections/docs_pages/points"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

        IngestRequest req = new IngestRequest(SourceType.PATH, "/tmp/test.pdf", null,
                "docs", null, 0L, "qdrant", null);
        pipeline.ingestPages(req, pdfFixture(1), "d-2");

        // No PUT to /collections/docs_pages (the create path).
        server.verify(0, putRequestedFor(urlEqualTo("/collections/docs_pages")));
        // But the points PUT did happen.
        server.verify(putRequestedFor(urlPathEqualTo("/collections/docs_pages/points")));
    }

    @Test
    void searchPages_embedsQuery_andRunsMultistage() throws Exception {
        server.stubFor(post(urlEqualTo("/embed_query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"vectors":[[0.7,0.3],[0.1,0.9]]}""")));
        server.stubFor(post(urlEqualTo("/collections/docs_pages/points/query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"points":[
                          {"id":"id-A","score":0.95,
                            "payload":{"doc_id":"d-1","page_number":3,
                                       "filename":"a.pdf","source":"https://x",
                                       "text_quality":2,"page_image_key":"docs/d-1/000003.png"}},
                          {"id":"id-B","score":0.82,
                            "payload":{"doc_id":"d-1","page_number":7,
                                       "filename":"a.pdf","source":"https://x",
                                       "text_quality":1,"page_image_key":"docs/d-1/000007.png"}}
                        ]}}""")));

        SearchRequest req = new SearchRequest("qdrant", "docs", "rate limiting", 5, null);
        List<PageHit> hits = pipeline.searchPages(req);

        assertThat(hits).hasSize(2);
        PageHit h0 = hits.get(0);
        assertThat(h0.score()).isCloseTo(0.95, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(h0.docId()).isEqualTo("d-1");
        assertThat(h0.pageNumber()).isEqualTo(3);
        assertThat(h0.filename()).isEqualTo("a.pdf");
        assertThat(h0.textQuality()).isEqualTo(2);
        assertThat(h0.pageImageKey()).isEqualTo("docs/d-1/000003.png");

        // Verify the multistage query body shape: prefetch on pooled_rows + pooled_cols,
        // rerank on original.
        var captured = server.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/collections/docs_pages/points/query"))
                .findFirst().orElseThrow();
        String body = new String(captured.getRequest().getBody());
        assertThat(body).contains("\"using\":\"pooled_rows\"");
        assertThat(body).contains("\"using\":\"pooled_cols\"");
        assertThat(body).contains("\"using\":\"original\"");
        assertThat(body).contains("\"limit\":5");
        // Prefetch limit = 10 × topK = 50.
        assertThat(body).contains("\"limit\":50");
    }

    @Test
    void dropVisualIndex_dropsCollectionAndImageFiles() throws Exception {
        // Pre-seed the image store as if a previous ingest had happened.
        FilesystemPageImageStore store = pipelineField(pipeline);
        store.store("docs", "d-1", 1, "p1".getBytes());
        store.store("docs", "d-1", 2, "p2".getBytes());
        store.store("docs", "d-2", 1, "p3".getBytes());

        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"config":{"params":{}}}}""")));
        server.stubFor(delete(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));

        ColPaliPipeline.DropResult result = pipeline.dropVisualIndex("docs");

        assertThat(result.collectionDropped()).isTrue();
        assertThat(result.filesRemoved()).isEqualTo(3);
        server.verify(deleteRequestedFor(urlEqualTo("/collections/docs_pages")));
        assertThat(Files.exists(tmpRoot.resolve("docs"))).isFalse();
    }

    @Test
    void dropVisualIndex_missingCollection_stillCleansImages() throws Exception {
        FilesystemPageImageStore store = pipelineField(pipeline);
        store.store("docs", "d-1", 1, "p1".getBytes());

        server.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(404)));

        ColPaliPipeline.DropResult result = pipeline.dropVisualIndex("docs");

        assertThat(result.collectionDropped()).isFalse();
        assertThat(result.filesRemoved()).isEqualTo(1);
    }

    @Test
    void getRenderedPage_returnsStoredBytes() throws Exception {
        FilesystemPageImageStore store = pipelineField(pipeline);
        byte[] png = "rendered-png-bytes".getBytes();
        store.store("docs", "d-1", 5, png);

        byte[] retrieved = pipeline.getRenderedPage("docs", "d-1", 5);

        assertThat(retrieved).isEqualTo(png);
    }

    @Test
    void getRenderedPage_missing_returnsNull() {
        assertThat(pipeline.getRenderedPage("docs", "d-missing", 1)).isNull();
    }

    @Test
    void inspectPage_returnsBase64AndDimensions() throws Exception {
        // Make a real 100x50 PNG and stash it via the store.
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                100, 50, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", baos);
        byte[] pngBytes = baos.toByteArray();

        FilesystemPageImageStore store = pipelineField(pipeline);
        store.store("docs", "d-1", 3, pngBytes);

        org.example.ingest.InspectPageResult result = pipeline.inspectPage("docs", "d-1", 3);

        assertThat(result).isNotNull();
        assertThat(result.kbName()).isEqualTo("docs");
        assertThat(result.docId()).isEqualTo("d-1");
        assertThat(result.pageNumber()).isEqualTo(3);
        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(50);
        // Base64 round-trip back to the original bytes.
        byte[] decoded = java.util.Base64.getDecoder().decode(result.base64Png());
        assertThat(decoded).isEqualTo(pngBytes);
    }

    @Test
    void inspectPage_missing_returnsNull() {
        assertThat(pipeline.inspectPage("docs", "d-missing", 1)).isNull();
    }

    @Test
    void pagesCollectionName_appliesSuffix() {
        assertThat(ColPaliPipeline.pagesCollectionName("docs"))
                .isEqualTo("docs_pages");
        assertThat(ColPaliPipeline.pagesCollectionName("engineering-docs"))
                .isEqualTo("engineering-docs_pages");
    }

    // ---- setup helpers ------------------------------------------------------

    private static ColPaliPipeline newPipeline(String baseUrl, String storeRoot) throws Exception {
        PageRasterizer rasterizer = new PageRasterizer();
        setField(rasterizer, "dpi", 72);
        setField(rasterizer, "imageType", "RGB");

        TextLayerProbe probe = new TextLayerProbe();
        setField(probe, "thresholdLow", 50);
        setField(probe, "thresholdFull", 500);

        ColPaliClient sidecar = new ColPaliClient();
        setField(sidecar, "baseUrl", baseUrl);
        setField(sidecar, "connectTimeoutSeconds", 5L);
        setField(sidecar, "requestTimeoutSeconds", 10L);
        setField(sidecar, "batchSize", 16);
        setField(sidecar, "objectMapper", new ObjectMapper());
        invokeInit(sidecar);

        QdrantClient qdrant = new QdrantClient();
        setField(qdrant, "baseUrl", baseUrl);
        setField(qdrant, "apiKey", "");
        setField(qdrant, "distance", "Cosine");
        setField(qdrant, "connectTimeoutSeconds", 5L);
        setField(qdrant, "requestTimeoutSeconds", 10L);
        setField(qdrant, "objectMapper", new ObjectMapper());
        invokeInit(qdrant);

        FilesystemPageImageStore store = new FilesystemPageImageStore();
        setField(store, "configuredImpl", "filesystem");
        setField(store, "rootPath", storeRoot);
        invokeInit(store);

        ColPaliPipeline pipeline = new ColPaliPipeline();
        setField(pipeline, "rasterizer", rasterizer);
        setField(pipeline, "textLayerProbe", probe);
        setField(pipeline, "sidecar", sidecar);
        setField(pipeline, "qdrant", qdrant);
        setField(pipeline, "imageStore", store);
        return pipeline;
    }

    private static FilesystemPageImageStore pipelineField(ColPaliPipeline pipeline) throws Exception {
        Field f = ColPaliPipeline.class.getDeclaredField("imageStore");
        f.setAccessible(true);
        return (FilesystemPageImageStore) f.get(pipeline);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Integer i && f.getType() == int.class) {
            f.setInt(target, i);
        } else {
            f.set(target, value);
        }
    }

    private static void invokeInit(Object o) throws Exception {
        var m = o.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        try {
            m.invoke(o);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private static FetchedFile pdfFixture(int numPages) throws IOException {
        return pdfFixture(numPages, p -> "Page " + p + " content");
    }

    private static FetchedFile pdfFixture(int numPages, IntFunction<String> content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= numPages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(content.apply(i));
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new FetchedFile("test.pdf", "application/pdf", baos.toByteArray());
        }
    }
}
