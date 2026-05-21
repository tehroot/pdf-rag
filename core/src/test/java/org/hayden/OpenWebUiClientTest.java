package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.hayden.backend.openwebui.OpenWebUiClient;
import org.hayden.backend.openwebui.OpenWebUiException;
import org.hayden.backend.openwebui.dto.KnowledgeBase;
import org.hayden.backend.openwebui.dto.ProcessStatus;
import org.hayden.backend.openwebui.dto.UploadedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenWebUiClientTest {

    private WireMockServer server;
    private OpenWebUiClient client;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = newClient(server.baseUrl(), "test-key");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void listKnowledgeBases_returnsParsedArray() {
        server.stubFor(get(urlEqualTo("/api/v1/knowledge/"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[{\"id\":\"kb-1\",\"name\":\"Alpha\",\"description\":\"\"},"
                                + "{\"id\":\"kb-2\",\"name\":\"Beta\"}],\"total\":2}")));

        List<KnowledgeBase> kbs = client.listKnowledgeBases();

        assertThat(kbs).extracting(KnowledgeBase::id).containsExactly("kb-1", "kb-2");
        assertThat(kbs.get(1).name()).isEqualTo("Beta");
    }

    @Test
    void createKnowledgeBase_sendsCorrectJsonBody() {
        server.stubFor(post(urlEqualTo("/api/v1/knowledge/create"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(
                        "{\"name\":\"Mine\",\"description\":\"hi\",\"data\":{},\"access_control\":{}}"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"id\":\"kb-9\",\"name\":\"Mine\",\"description\":\"hi\"}")));

        KnowledgeBase kb = client.createKnowledgeBase("Mine", "hi");

        assertThat(kb.id()).isEqualTo("kb-9");
        assertThat(kb.name()).isEqualTo("Mine");
    }

    @Test
    void uploadFile_sendsMultipartAndReturnsId() {
        server.stubFor(post(urlEqualTo("/api/v1/files/"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"id\":\"file-77\",\"filename\":\"doc.pdf\"}")));

        UploadedFile uploaded = client.uploadFile("doc.pdf", "application/pdf", "PDF-BYTES".getBytes());

        assertThat(uploaded.id()).isEqualTo("file-77");
        server.verify(postRequestedFor(urlEqualTo("/api/v1/files/")));
        var captured = server.getAllServeEvents().get(0).getRequest();
        String body = new String(captured.getBody());
        String contentType = captured.getHeader("Content-Type");
        assertThat(contentType).startsWith("multipart/form-data; boundary=");
        assertThat(body).contains("form-data; name=\"file\"; filename=\"doc.pdf\"");
        assertThat(body).contains("Content-Type: application/pdf");
        assertThat(body).contains("PDF-BYTES");
    }

    @Test
    void getFileProcessStatus_parsesStatusAndError() {
        server.stubFor(get(urlEqualTo("/api/v1/files/file-1/process/status"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"status\":\"failed\",\"error\":\"parse blew up\"}")));

        ProcessStatus s = client.getFileProcessStatus("file-1");
        assertThat(s.failed()).isTrue();
        assertThat(s.error()).isEqualTo("parse blew up");
    }

    @Test
    void addFileToKnowledgeBase_postsFileId() {
        server.stubFor(post(urlEqualTo("/api/v1/knowledge/kb-1/file/add"))
                .withRequestBody(equalToJson("{\"file_id\":\"file-2\"}"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        client.addFileToKnowledgeBase("kb-1", "file-2");
        server.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/kb-1/file/add")));
    }

    @Test
    void non2xxResponse_throwsOpenWebUiException() {
        server.stubFor(get(urlEqualTo("/api/v1/knowledge/"))
                .willReturn(aResponse().withStatus(401).withBody("{\"detail\":\"401 Unauthorized\"}")));

        assertThatThrownBy(() -> client.listKnowledgeBases())
                .isInstanceOf(OpenWebUiException.class)
                .hasMessageContaining("401");
    }

    static OpenWebUiClient newClient(String baseUrl, String apiKey) throws Exception {
        OpenWebUiClient c = new OpenWebUiClient();
        setField(c, "baseUrl", baseUrl);
        setField(c, "apiKey", apiKey);
        setField(c, "connectTimeoutSeconds", 5L);
        setField(c, "requestTimeoutSeconds", 10L);
        setField(c, "objectMapper", new ObjectMapper());
        var init = c.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(c);
        return c;
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
