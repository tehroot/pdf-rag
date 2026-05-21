package org.hayden.backend.openwebui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.openwebui.dto.AddFileRequest;
import org.hayden.backend.openwebui.dto.CreateKbRequest;
import org.hayden.backend.openwebui.dto.KnowledgeBase;
import org.hayden.backend.openwebui.dto.KnowledgePage;
import org.hayden.backend.openwebui.dto.ProcessStatus;
import org.hayden.backend.openwebui.dto.UploadedFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OpenWebUiClient {

    private static final String CRLF = "\r\n";

    @ConfigProperty(name = "ingest.openwebui.base-url")
    String baseUrl;

    @ConfigProperty(name = "ingest.openwebui.api-key")
    String apiKey;

    @ConfigProperty(name = "ingest.openwebui.connect-timeout-seconds", defaultValue = "10")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "ingest.openwebui.request-timeout-seconds", defaultValue = "120")
    long requestTimeoutSeconds;

    @Inject
    ObjectMapper objectMapper;

    private HttpClient http;

    @PostConstruct
    void init() {
        // Force HTTP/1.1: java.net.http defaults to HTTP/2 and sends cleartext h2c
        // upgrade headers on plain HTTP requests, which Open WebUI's uvicorn/httptools
        // rejects with `400 Invalid HTTP request received`.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        HttpRequest req = baseRequest("/api/v1/knowledge/")
                .GET()
                .build();
        KnowledgePage page = sendForJson(req, new TypeReference<KnowledgePage>() {
        });
        return page.items() == null ? List.of() : page.items();
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        byte[] body = writeJson(CreateKbRequest.of(name, description));
        HttpRequest req = baseRequest("/api/v1/knowledge/create")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return sendForJson(req, new TypeReference<KnowledgeBase>() {
        });
    }

    public UploadedFile uploadFile(String filename, String contentType, byte[] content) {
        String boundary = "----openwebui-" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, filename, contentType, content);

        HttpRequest req = baseRequest("/api/v1/files/")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return sendForJson(req, new TypeReference<UploadedFile>() {
        });
    }

    public ProcessStatus getFileProcessStatus(String fileId) {
        HttpRequest req = baseRequest("/api/v1/files/" + urlEncode(fileId) + "/process/status")
                .GET()
                .build();
        return sendForJson(req, new TypeReference<ProcessStatus>() {
        });
    }

    public void addFileToKnowledgeBase(String knowledgeBaseId, String fileId) {
        byte[] body = writeJson(new AddFileRequest(fileId));
        HttpRequest req = baseRequest("/api/v1/knowledge/" + urlEncode(knowledgeBaseId) + "/file/add")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        sendExpectingSuccess(req);
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
    }

    private <T> T sendForJson(HttpRequest req, TypeReference<T> type) {
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new OpenWebUiException(resp.statusCode(),
                        "Open WebUI returned " + resp.statusCode() + " for "
                                + req.method() + " " + req.uri() + ": "
                                + new String(resp.body(), StandardCharsets.UTF_8));
            }
            return objectMapper.readValue(resp.body(), type);
        } catch (IOException e) {
            throw new OpenWebUiException("I/O error calling " + req.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenWebUiException("Interrupted calling " + req.uri(), e);
        }
    }

    private void sendExpectingSuccess(HttpRequest req) {
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new OpenWebUiException(resp.statusCode(),
                        "Open WebUI returned " + resp.statusCode() + " for "
                                + req.method() + " " + req.uri() + ": "
                                + new String(resp.body(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new OpenWebUiException("I/O error calling " + req.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenWebUiException("Interrupted calling " + req.uri(), e);
        }
    }

    private byte[] writeJson(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new OpenWebUiException("Failed to serialize JSON", e);
        }
    }

    private static byte[] buildMultipartBody(String boundary, String filename,
                                             String contentType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 256);
        String header = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename.replace("\"", "_") + "\"" + CRLF
                + "Content-Type: " + contentType + CRLF
                + CRLF;
        String trailer = CRLF + "--" + boundary + "--" + CRLF;
        try {
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(trailer.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return out.toByteArray();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String urlEncode(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
