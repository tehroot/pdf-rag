package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.hayden.backend.qdrant.ChunkPipeline;
import org.hayden.backend.qdrant.Chunker;
import org.hayden.backend.qdrant.ColPaliPipeline;
import org.hayden.backend.qdrant.Embedder;
import org.hayden.backend.qdrant.PageRasterizer;
import org.hayden.backend.qdrant.QdrantBackend;
import org.hayden.backend.qdrant.QdrantClient;
import org.hayden.backend.qdrant.TextExtractor;
import org.hayden.backend.qdrant.TextLayerProbe;
import org.hayden.backend.KnowledgeBaseSummary;
import org.hayden.backend.qdrant.fusion.ConfidenceCalculator;
import org.hayden.backend.qdrant.fusion.FusionEngine;
import org.hayden.backend.qdrant.fusion.FusionStrategy;
import org.hayden.backend.qdrant.fusion.RrfFusion;
import org.hayden.backend.qdrant.fusion.WeightedScoreFusion;
import org.hayden.jobs.IngestQueue;
import org.hayden.ingest.FileFetcher;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.SearchHit;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class QdrantBackendTest {

    /**
     * One WireMock server stands in for both the embeddings backend (llama-server's
     * /v1/embeddings) and Qdrant (under /collections...). Realistic enough; the two
     * are distinguished by URL.
     */
    private WireMockServer mock;
    private QdrantBackend backend;

    @BeforeEach
    void start() throws Exception {
        mock = new WireMockServer(options().dynamicPort());
        mock.start();
        backend = newBackend(mock.baseUrl());
    }

    @AfterEach
    void stop() {
        mock.stop();
    }

    @Test
    void ingest_inlineText_createsCollectionAndUpsertsChunks() {
        // With chunker size=30/overlap=5 (see newBackend), this text produces 3 chunks.
        // All chunks go in one embeddings batch (batchSize=64), so we stub 3 vectors.
        mock.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("""
                                {"data":[
                                  {"embedding":[0.5,0.5,0.5]},
                                  {"embedding":[0.5,0.5,0.5]},
                                  {"embedding":[0.5,0.5,0.5]}
                                ]}""")));

        // Qdrant: collection does not exist, then PUT to create, then PUT to upsert.
        mock.stubFor(get(urlEqualTo("/collections/docs"))
                .willReturn(aResponse().withStatus(404)));
        mock.stubFor(put(urlEqualTo("/collections/docs"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));
        mock.stubFor(put(urlPathEqualTo("/collections/docs/points"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

        String text = "Alpha alpha alpha. Beta beta beta. Gamma gamma gamma gamma.";
        String b64 = Base64.getEncoder().encodeToString(text.getBytes());

        IngestResult r = backend.ingest(new IngestRequest(
                SourceType.INLINE, b64, "notes.txt",
                "docs", null, 0L, "qdrant", Map.of("project", "alpha")));

        assertThat(r.backend()).isEqualTo("qdrant");
        assertThat(r.kbName()).isEqualTo("docs");
        assertThat(r.addedToKb()).isTrue();
        assertThat(r.chunkCount()).isEqualTo(3);
        mock.verify(putRequestedFor(urlEqualTo("/collections/docs")));
        mock.verify(putRequestedFor(urlPathEqualTo("/collections/docs/points")));
        mock.verify(postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void ingest_path_writesPayloadWithChunkText() throws Exception {
        Path tmp = Files.createTempFile("qb-", ".txt");
        Files.writeString(tmp, "alpha beta gamma. delta epsilon zeta. eta theta iota kappa.");
        try {
            mock.stubFor(post(urlEqualTo("/v1/embeddings"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("""
                                    {"data":[
                                      {"embedding":[0.1,0.2,0.3]},
                                      {"embedding":[0.1,0.2,0.3]},
                                      {"embedding":[0.1,0.2,0.3]}
                                    ]}""")));
            mock.stubFor(get(urlEqualTo("/collections/notes"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"result":{
                              "config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}
                            }}""")));
            mock.stubFor(put(urlPathEqualTo("/collections/notes/points"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

            IngestResult r = backend.ingest(new IngestRequest(
                    SourceType.PATH, tmp.toAbsolutePath().toString(), null,
                    "notes", null, 0L, "qdrant", null));

            assertThat(r.addedToKb()).isTrue();
            // No collection-create call because the collection already existed.
            mock.verify(0, putRequestedFor(urlEqualTo("/collections/notes")));

            // Verify the upsert payload mentions our filename and chunk metadata.
            var captured = mock.getAllServeEvents().stream()
                    .filter(e -> e.getRequest().getUrl().startsWith("/collections/notes/points"))
                    .findFirst().orElseThrow();
            String body = new String(captured.getRequest().getBody());
            assertThat(body).contains("\"filename\":\"" + tmp.getFileName() + "\"");
            assertThat(body).contains("\"doc_id\"");
            assertThat(body).contains("\"chunk_index\"");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void search_embedsQueryThenCallsQdrantSearch() {
        mock.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"data\":[{\"embedding\":[0.9,0.1,0.0]}]}")));
        mock.stubFor(post(urlEqualTo("/collections/docs/points/search"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":[
                          {"id":"id-1","score":0.92,
                            "payload":{"text":"hello world","filename":"a.txt",
                                       "doc_id":"d-1","chunk_index":0,"source":"inline"}}
                        ]}""")));

        SearchResponse resp = backend.search(new SearchRequest(
                "qdrant", "docs", "say hi", 5, null));

        assertThat(resp.backend()).isEqualTo("qdrant");
        assertThat(resp.hits()).hasSize(1);
        SearchHit hit = resp.hits().get(0);
        assertThat(hit.text()).isEqualTo("hello world");
        assertThat(hit.filename()).isEqualTo("a.txt");
        assertThat(hit.docId()).isEqualTo("d-1");
        assertThat(hit.chunkIndex()).isZero();
        assertThat(hit.score()).isCloseTo(0.92, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void listKnowledgeBases_includesDimAndVectorCount() {
        // Two KBs: "docs" has no visual index, "scans" has one.
        mock.stubFor(get(urlEqualTo("/collections"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{"collections":[
                          {"name":"docs"},
                          {"name":"scans"},
                          {"name":"scans_pages"}
                        ]}}""")));
        mock.stubFor(get(urlEqualTo("/collections/docs"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "vectors_count": 17,
                          "config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}
                        }}""")));
        mock.stubFor(get(urlEqualTo("/collections/scans"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "vectors_count": 42,
                          "config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}
                        }}""")));
        mock.stubFor(get(urlEqualTo("/collections/docs_pages"))
                .willReturn(aResponse().withStatus(404)));
        mock.stubFor(get(urlEqualTo("/collections/scans_pages"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"result":{
                          "points_count": 200,
                          "vectors_count": 6600,
                          "config":{"params":{}}
                        }}""")));

        var out = backend.listKnowledgeBases();

        // Internal <kb>_pages collections are filtered out of the top-level list.
        assertThat(out).extracting(KnowledgeBaseSummary::name)
                .containsExactlyInAnyOrder("docs", "scans");

        var docs = out.stream().filter(k -> "docs".equals(k.name())).findFirst().orElseThrow();
        assertThat(docs.vectors()).isEqualTo(17L);
        assertThat(docs.dim()).isEqualTo(3);
        assertThat(docs.visualIndexEnabled()).isFalse();
        assertThat(docs.visualIndexPages()).isNull();

        var scans = out.stream().filter(k -> "scans".equals(k.name())).findFirst().orElseThrow();
        assertThat(scans.visualIndexEnabled()).isTrue();
        assertThat(scans.visualIndexPages()).isEqualTo(200L);
    }

    @Test
    void pointIdFor_isDeterministic() {
        String id1 = QdrantBackend.pointIdFor("doc-1", 0);
        String id2 = QdrantBackend.pointIdFor("doc-1", 0);
        String id3 = QdrantBackend.pointIdFor("doc-1", 1);
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        // UUID format sanity
        assertThat(id1).matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    // ---- visual-enabled orchestration tests ---------------------------------

    @Test
    void ingest_visualEnabled_runsBothPipelinesAndAttachesPagesIngest() throws Exception {
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("qb-visual-");
        try {
            QdrantBackend visualBackend = newBackendWithVisual(mock.baseUrl(), tmpRoot);

            // Sidecar healthy.
            mock.stubFor(get(urlEqualTo("/healthz"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\",\"ready\":true}")));
            mock.stubFor(get(urlEqualTo("/info"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"model_name":"vidore/colqwen2-v1.0","vector_dim":2,
                             "supports_pooled":true,"max_batch_size":8,"device":"cpu"}""")));

            // Text embeddings: chunks for a tiny PDF (1 page, sparse content → 1 chunk).
            mock.stubFor(post(urlEqualTo("/v1/embeddings"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"data":[{"embedding":[0.1,0.2,0.3]}]}""")));

            // Visual embedding for the rendered page.
            mock.stubFor(post(urlEqualTo("/embed_pages"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"embeddings":[
                              {"page_id":"x","original":[[0.1,0.2]],"pooled_rows":[[0.1,0.2]],"pooled_cols":[[0.1,0.2]]}
                            ]}""")));

            // Qdrant: fresh KB; both collections need to be created.
            mock.stubFor(get(urlEqualTo("/collections/v-kb"))
                    .willReturn(aResponse().withStatus(404)));
            mock.stubFor(get(urlEqualTo("/collections/v-kb_pages"))
                    .willReturn(aResponse().withStatus(404)));
            mock.stubFor(put(urlEqualTo("/collections/v-kb"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));
            mock.stubFor(put(urlEqualTo("/collections/v-kb_pages"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));
            mock.stubFor(put(urlPathEqualTo("/collections/v-kb/points"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));
            mock.stubFor(put(urlPathEqualTo("/collections/v-kb_pages/points"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

            byte[] pdfBytes = makeTinyPdf(1);
            java.nio.file.Path tmpPdf = java.nio.file.Files.createTempFile("qb-visual-", ".pdf");
            java.nio.file.Files.write(tmpPdf, pdfBytes);
            try {
                IngestRequest req = new IngestRequest(
                        SourceType.PATH, tmpPdf.toAbsolutePath().toString(), null,
                        "v-kb", null, 0L, "qdrant", null, true);
                IngestResult r = visualBackend.ingest(req);

                assertThat(r.backend()).isEqualTo("qdrant");
                assertThat(r.kbName()).isEqualTo("v-kb");
                assertThat(r.chunkCount()).isGreaterThan(0);
                assertThat(r.pageCount()).isEqualTo(1);
                assertThat(r.addedToKb()).isTrue();
                assertThat(r.warnings()).isEmpty();
                mock.verify(putRequestedFor(urlEqualTo("/collections/v-kb_pages")));
                mock.verify(putRequestedFor(urlPathEqualTo("/collections/v-kb_pages/points")));
            } finally {
                java.nio.file.Files.deleteIfExists(tmpPdf);
            }
        } finally {
            deleteTree(tmpRoot);
        }
    }

    @Test
    void ingest_visualEnabled_nonPdf_skipsVisualWithWarning() throws Exception {
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("qb-visual-");
        try {
            QdrantBackend visualBackend = newBackendWithVisual(mock.baseUrl(), tmpRoot);

            mock.stubFor(get(urlEqualTo("/healthz"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\",\"ready\":true}")));
            mock.stubFor(post(urlEqualTo("/v1/embeddings"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"data":[{"embedding":[0.1,0.2,0.3]}]}""")));
            mock.stubFor(get(urlEqualTo("/collections/txt-kb"))
                    .willReturn(aResponse().withStatus(404)));
            mock.stubFor(get(urlEqualTo("/collections/txt-kb_pages"))
                    .willReturn(aResponse().withStatus(404)));
            mock.stubFor(put(urlEqualTo("/collections/txt-kb"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":true}")));
            mock.stubFor(put(urlPathEqualTo("/collections/txt-kb/points"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"result\":{}}")));

            // Inline text content (not PDF).
            String b64 = Base64.getEncoder().encodeToString("hello world".getBytes());
            IngestRequest req = new IngestRequest(
                    SourceType.INLINE, b64, "notes.txt",
                    "txt-kb", null, 0L, "qdrant", null, true);
            IngestResult r = visualBackend.ingest(req);

            assertThat(r.chunkCount()).isGreaterThan(0);
            assertThat(r.pageCount()).isZero();
            assertThat(r.warnings()).anyMatch(w -> w.contains("not a PDF"));
            // Visual collection should NOT have been created.
            mock.verify(0, putRequestedFor(urlEqualTo("/collections/txt-kb_pages")));
        } finally {
            deleteTree(tmpRoot);
        }
    }

    @Test
    void ingest_modeMismatch_existingVisualKb_throws() throws Exception {
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("qb-visual-");
        try {
            QdrantBackend visualBackend = newBackendWithVisual(mock.baseUrl(), tmpRoot);

            // KB already has both chunk and visual collections.
            mock.stubFor(get(urlEqualTo("/collections/existing"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"result":{"config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}}}""")));
            mock.stubFor(get(urlEqualTo("/collections/existing_pages"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"result":{"config":{"params":{}}}}""")));

            String b64 = Base64.getEncoder().encodeToString("text".getBytes());
            IngestRequest req = new IngestRequest(
                    SourceType.INLINE, b64, "notes.txt",
                    "existing", null, 0L, "qdrant", null, false);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> visualBackend.ingest(req))
                    .isInstanceOf(org.hayden.ingest.IngestException.class)
                    .hasMessageContaining("visual index enabled")
                    .hasMessageContaining("enable_visual_index=false");
        } finally {
            deleteTree(tmpRoot);
        }
    }

    @Test
    void ingest_modeMismatch_existingTextOnlyKb_throws() throws Exception {
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("qb-visual-");
        try {
            QdrantBackend visualBackend = newBackendWithVisual(mock.baseUrl(), tmpRoot);

            // KB has chunks but no visual.
            mock.stubFor(get(urlEqualTo("/collections/text-only"))
                    .willReturn(aResponse().withStatus(200).withBody("""
                            {"result":{"config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}}}""")));
            mock.stubFor(get(urlEqualTo("/collections/text-only_pages"))
                    .willReturn(aResponse().withStatus(404)));

            String b64 = Base64.getEncoder().encodeToString("text".getBytes());
            IngestRequest req = new IngestRequest(
                    SourceType.INLINE, b64, "notes.txt",
                    "text-only", null, 0L, "qdrant", null, true);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> visualBackend.ingest(req))
                    .isInstanceOf(org.hayden.ingest.IngestException.class)
                    .hasMessageContaining("without a visual index")
                    .hasMessageContaining("enable_visual_index=false");
        } finally {
            deleteTree(tmpRoot);
        }
    }

    @Test
    void ingest_visualRequested_sidecarDown_hardFails() throws Exception {
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("qb-visual-");
        try {
            QdrantBackend visualBackend = newBackendWithVisual(mock.baseUrl(), tmpRoot);

            // Sidecar unhealthy.
            mock.stubFor(get(urlEqualTo("/healthz"))
                    .willReturn(aResponse().withStatus(503)));

            // Fresh KB, no existing collections.
            mock.stubFor(get(urlEqualTo("/collections/v-kb"))
                    .willReturn(aResponse().withStatus(404)));
            mock.stubFor(get(urlEqualTo("/collections/v-kb_pages"))
                    .willReturn(aResponse().withStatus(404)));

            String b64 = Base64.getEncoder().encodeToString(makeTinyPdf(1));
            IngestRequest req = new IngestRequest(
                    SourceType.INLINE, b64, "x.pdf",
                    "v-kb", null, 0L, "qdrant", null, true);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> visualBackend.ingest(req))
                    .isInstanceOf(org.hayden.ingest.IngestException.class)
                    .hasMessageContaining("sidecar is unreachable");
            // No collection should have been created — we fail BEFORE any ingest happens.
            mock.verify(0, putRequestedFor(urlEqualTo("/collections/v-kb")));
        } finally {
            deleteTree(tmpRoot);
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** Builds a backend with the visual path fully wired (sidecar + image store). */
    private static QdrantBackend newBackendWithVisual(String baseUrl, java.nio.file.Path tmpRoot) throws Exception {
        QdrantBackend backend = newBackend(baseUrl);

        // Build the visual side and inject.
        PageRasterizer rasterizer = new PageRasterizer();
        setField(rasterizer, "dpi", 72);
        setField(rasterizer, "imageType", "RGB");

        TextLayerProbe probe = new TextLayerProbe();
        setField(probe, "thresholdLow", 50);
        setField(probe, "thresholdFull", 500);

        org.hayden.backend.qdrant.ColPaliClient sidecar = new org.hayden.backend.qdrant.ColPaliClient();
        setField(sidecar, "baseUrl", baseUrl);
        setField(sidecar, "connectTimeoutSeconds", 5L);
        setField(sidecar, "requestTimeoutSeconds", 10L);
        setField(sidecar, "batchSize", 16);
        setField(sidecar, "objectMapper", new ObjectMapper());
        invokeInit(sidecar);

        QdrantClient qdrant = (QdrantClient) getField(getField(backend, "chunks"), "qdrant");

        org.hayden.backend.qdrant.FilesystemPageImageStore store =
                new org.hayden.backend.qdrant.FilesystemPageImageStore();
        setField(store, "configuredImpl", "filesystem");
        setField(store, "rootPath", tmpRoot.toString());
        invokeInit(store);

        ColPaliPipeline pages = new ColPaliPipeline();
        setField(pages, "rasterizer", rasterizer);
        setField(pages, "textLayerProbe", probe);
        setField(pages, "sidecar", sidecar);
        setField(pages, "qdrant", qdrant);
        setField(pages, "imageStore", store);

        setField(backend, "pages", pages);
        setField(backend, "defaultVisualIndexEnabled", false);
        return backend;
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static byte[] makeTinyPdf(int numPages) throws java.io.IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 1; i <= numPages; i++) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                        org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                             new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("page " + i);
                    cs.endText();
                }
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static void deleteTree(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.exists(dir)) return;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
            });
        } catch (java.io.IOException ignored) {
        }
    }

    /** Wire everything by reflection — same pattern as the other tests. */
    private static QdrantBackend newBackend(String baseUrl) throws Exception {
        FileFetcher fetcher = new FileFetcher();
        setField(fetcher, "maxFileBytes", 100L * 1024 * 1024);
        setField(fetcher, "connectTimeoutSeconds", 5L);
        setField(fetcher, "requestTimeoutSeconds", 10L);
        invokeInit(fetcher);

        TextExtractor extractor = new TextExtractor();
        Field maxChars = TextExtractor.class.getDeclaredField("maxChars");
        maxChars.setAccessible(true);
        maxChars.setInt(extractor, 1_000_000);

        Chunker chunker = new Chunker();
        Field s = Chunker.class.getDeclaredField("sizeChars");
        s.setAccessible(true);
        s.setInt(chunker, 30);
        Field o = Chunker.class.getDeclaredField("overlapChars");
        o.setAccessible(true);
        o.setInt(chunker, 5);

        Embedder embedder = new Embedder();
        setField(embedder, "baseUrl", baseUrl + "/v1");
        setField(embedder, "apiKey", "");
        setField(embedder, "model", "test-embed");
        setField(embedder, "batchSize", 64);
        setField(embedder, "connectTimeoutSeconds", 5L);
        setField(embedder, "requestTimeoutSeconds", 10L);
        setField(embedder, "objectMapper", new ObjectMapper());
        invokeInit(embedder);

        QdrantClient qdrant = new QdrantClient();
        setField(qdrant, "baseUrl", baseUrl);
        setField(qdrant, "apiKey", "");
        setField(qdrant, "distance", "Cosine");
        setField(qdrant, "connectTimeoutSeconds", 5L);
        setField(qdrant, "requestTimeoutSeconds", 10L);
        setField(qdrant, "objectMapper", new ObjectMapper());
        invokeInit(qdrant);

        ChunkPipeline chunks = new ChunkPipeline();
        setField(chunks, "extractor", extractor);
        setField(chunks, "chunker", chunker);
        setField(chunks, "embedder", embedder);
        setField(chunks, "qdrant", qdrant);

        // Existing tests use the text path only and don't enable visual index.
        // Wire a no-op ColPaliPipeline that reports the KB has no visual index
        // and treats the sidecar as unhealthy; with defaultVisualIndexEnabled=false
        // these never get exercised in the existing test cases.
        ColPaliPipeline pages = new ColPaliPipeline();
        setField(pages, "rasterizer", new PageRasterizer());
        setField(pages, "textLayerProbe", new TextLayerProbe());
        setField(pages, "qdrant", qdrant);
        // sidecar + imageStore stay null — they're only touched when visual is enabled.

        // Wire FusionEngine with strategies + ConfidenceCalculator. Existing
        // tests that hit the search path go through this even though most
        // exercise text-only mode (no visual index → mode resolves to text_only).
        ConfidenceCalculator confidence = new ConfidenceCalculator();
        setField(confidence, "weightText", 0.4);
        setField(confidence, "weightVisual", 0.4);
        setField(confidence, "weightAgreement", 0.2);
        setField(confidence, "thresholdHigh", 0.7);
        setField(confidence, "thresholdMedium", 0.4);
        setField(confidence, "textScoreFloor", 1.0);
        setField(confidence, "visualScoreFloor", 50.0);

        FusionEngine fusionEngine = new FusionEngine();
        setField(fusionEngine, "chunks", chunks);
        setField(fusionEngine, "pages", pages);
        setField(fusionEngine, "confidence", confidence);
        setField(fusionEngine, "strategies",
                new TestInstance<>(java.util.List.of(new RrfFusion(), new WeightedScoreFusion())));
        setField(fusionEngine, "defaultMode", "auto");
        setField(fusionEngine, "defaultStrategyName", "rrf");
        setField(fusionEngine, "rrfK", 60);
        setField(fusionEngine, "weightedText", 0.5);
        setField(fusionEngine, "weightedVisual", 0.5);
        setField(fusionEngine, "textScoreFloor", 1.0);
        setField(fusionEngine, "visualScoreFloor", 50.0);
        setField(fusionEngine, "nTextMultiplier", 4);
        setField(fusionEngine, "nPagesMultiplier", 2);

        // Async queue — wired but kept off the hot path for existing tests
        // (threshold = Integer.MAX_VALUE means every PDF runs synchronously).
        IngestQueue queue = new IngestQueue();
        java.nio.file.Path queueDir;
        try {
            queueDir = java.nio.file.Files.createTempDirectory("qb-queue-");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        setField(queue, "persistencePath", queueDir.toString());
        setField(queue, "maxRetries", 3);
        setField(queue, "objectMapper",
                new ObjectMapper().registerModule(
                        new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
        try {
            var init = IngestQueue.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(queue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        QdrantBackend backend = new QdrantBackend();
        setField(backend, "fetcher", fetcher);
        setField(backend, "chunks", chunks);
        setField(backend, "pages", pages);
        setField(backend, "fusion", fusionEngine);
        setField(backend, "queue", queue);
        setField(backend, "defaultVisualIndexEnabled", false);
        setField(backend, "syncThresholdPages", Integer.MAX_VALUE);
        return backend;
    }

    /** Minimal CDI Instance<T> stand-in for tests. Iterates over a backing list. */
    private static final class TestInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final java.util.List<T> backing;

        TestInstance(java.util.List<T> backing) {
            this.backing = backing;
        }

        @Override public java.util.Iterator<T> iterator() { return backing.iterator(); }
        @Override public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> c, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> t, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return backing.isEmpty(); }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) { }
        @Override public jakarta.enterprise.inject.Instance.Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() { return java.util.Collections.emptyList(); }
        @Override public T get() { return backing.isEmpty() ? null : backing.get(0); }
    }

    private static void invokeInit(Object o) throws Exception {
        var m = o.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(o);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
