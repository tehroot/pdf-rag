package org.hayden;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.hayden.backend.openwebui.FileUploadService;
import org.hayden.backend.openwebui.KnowledgeService;
import org.hayden.backend.openwebui.OpenWebUiBackend;
import org.hayden.backend.openwebui.OpenWebUiClient;
import org.hayden.ingest.FileFetcher;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class OpenWebUiBackendTest {

    private WireMockServer server;
    private OpenWebUiBackend backend;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        backend = newBackend(server.baseUrl());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void happyPath_reusesExistingKbAndAttachesFile() throws Exception {
        Path local = Files.createTempFile("ingest-svc-", ".pdf");
        Files.writeString(local, "pdf-bytes");
        try {
            server.stubFor(get(urlEqualTo("/api/v1/knowledge/"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"items\":[{\"id\":\"kb-1\",\"name\":\"docs\"}],\"total\":1}")));
            server.stubFor(post(urlEqualTo("/api/v1/files/"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"id\":\"file-1\",\"filename\":\"x.pdf\"}")));
            server.stubFor(get(urlEqualTo("/api/v1/files/file-1/process/status"))
                    .inScenario("poll").whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"pending\"}"))
                    .willSetStateTo("done"));
            server.stubFor(get(urlEqualTo("/api/v1/files/file-1/process/status"))
                    .inScenario("poll").whenScenarioStateIs("done")
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"completed\"}")));
            server.stubFor(post(urlEqualTo("/api/v1/knowledge/kb-1/file/add"))
                    .withRequestBody(equalToJson("{\"file_id\":\"file-1\"}"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            IngestResult result = backend.ingest(new IngestRequest(
                    SourceType.PATH, local.toAbsolutePath().toString(), null,
                    "docs", null, 30L, "openwebui", null));

            assertThat(result.backend()).isEqualTo("openwebui");
            assertThat(result.kbId()).isEqualTo("kb-1");
            assertThat(result.fileId()).isEqualTo("file-1");
            assertThat(result.processingStatus()).isEqualTo("completed");
            assertThat(result.addedToKb()).isTrue();
            server.verify(0, postRequestedFor(urlEqualTo("/api/v1/knowledge/create")));
            server.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/kb-1/file/add")));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    void createsKbWhenNameNotFound() throws Exception {
        Path local = Files.createTempFile("ingest-svc-", ".pdf");
        Files.writeString(local, "pdf-bytes");
        try {
            server.stubFor(get(urlEqualTo("/api/v1/knowledge/"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"items\":[],\"total\":0}")));
            server.stubFor(post(urlEqualTo("/api/v1/knowledge/create"))
                    .withRequestBody(equalToJson(
                            "{\"name\":\"new-kb\",\"description\":\"made by test\",\"data\":{},\"access_control\":{}}"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"id\":\"kb-new\",\"name\":\"new-kb\"}")));
            server.stubFor(post(urlEqualTo("/api/v1/files/"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"id\":\"file-9\",\"filename\":\"x.pdf\"}")));
            server.stubFor(get(urlEqualTo("/api/v1/files/file-9/process/status"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"completed\"}")));
            server.stubFor(post(urlEqualTo("/api/v1/knowledge/kb-new/file/add"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            IngestResult result = backend.ingest(new IngestRequest(
                    SourceType.PATH, local.toAbsolutePath().toString(), null,
                    "new-kb", "made by test", 10L, "openwebui", null));

            assertThat(result.kbId()).isEqualTo("kb-new");
            assertThat(result.addedToKb()).isTrue();
            server.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/create")));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    void failedProcessing_surfacesErrorAndSkipsAdd() throws Exception {
        Path local = Files.createTempFile("ingest-svc-", ".pdf");
        Files.writeString(local, "pdf-bytes");
        try {
            server.stubFor(get(urlEqualTo("/api/v1/knowledge/"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"items\":[{\"id\":\"kb-1\",\"name\":\"docs\"}],\"total\":1}")));
            server.stubFor(post(urlEqualTo("/api/v1/files/"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"id\":\"file-bad\",\"filename\":\"x.pdf\"}")));
            server.stubFor(get(urlEqualTo("/api/v1/files/file-bad/process/status"))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("{\"status\":\"failed\",\"error\":\"unreadable\"}")));

            IngestResult result = backend.ingest(new IngestRequest(
                    SourceType.PATH, local.toAbsolutePath().toString(), null,
                    "docs", null, 5L, "openwebui", null));

            assertThat(result.addedToKb()).isFalse();
            assertThat(result.processingStatus()).isEqualTo("failed");
            assertThat(result.message()).contains("unreadable");
            server.verify(0, postRequestedFor(urlEqualTo("/api/v1/knowledge/kb-1/file/add")));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static OpenWebUiBackend newBackend(String baseUrl) throws Exception {
        OpenWebUiClient client = OpenWebUiClientTest.newClient(baseUrl, "test-key");

        FileFetcher fetcher = new FileFetcher();
        setField(fetcher, "maxFileBytes", 100L * 1024 * 1024);
        setField(fetcher, "connectTimeoutSeconds", 5L);
        setField(fetcher, "requestTimeoutSeconds", 10L);
        var init = fetcher.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(fetcher);

        KnowledgeService kbSvc = new KnowledgeService();
        setField(kbSvc, "client", client);

        FileUploadService uploadSvc = new FileUploadService();
        setField(uploadSvc, "client", client);
        setField(uploadSvc, "initialPollMs", 1L);
        setField(uploadSvc, "maxPollMs", 5L);

        OpenWebUiBackend backend = new OpenWebUiBackend();
        setField(backend, "fetcher", fetcher);
        setField(backend, "kbService", kbSvc);
        setField(backend, "uploadService", uploadSvc);
        setField(backend, "client", client);
        return backend;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
