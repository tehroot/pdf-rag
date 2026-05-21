package org.hayden.ingest;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class FileFetcher {

    private static final Map<String, String> EXT_CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "txt", "text/plain",
            "md", "text/markdown",
            "html", "text/html",
            "htm", "text/html",
            "json", "application/json",
            "csv", "text/csv",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    @ConfigProperty(name = "ingest.max-file-bytes", defaultValue = "104857600")
    long maxFileBytes;

    @ConfigProperty(name = "ingest.fetch.connect-timeout-seconds", defaultValue = "10")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "ingest.fetch.request-timeout-seconds", defaultValue = "120")
    long requestTimeoutSeconds;

    private HttpClient http;

    @PostConstruct
    void init() {
        // Force HTTP/1.1 to avoid h2c upgrade headers being rejected by strict
        // origins (e.g. uvicorn-backed services). See OpenWebUiClient for context.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    public FetchedFile fromUrl(String url, String overrideFilename) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IngestException("Invalid URL: " + url, e);
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IngestException("Only http and https URLs are supported, got: " + uri.getScheme());
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IngestException("Failed to download " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted downloading " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IngestException("Download of " + url + " returned HTTP " + resp.statusCode());
        }
        byte[] body = resp.body();
        enforceSize(body.length, url);

        String filename = overrideFilename != null && !overrideFilename.isBlank()
                ? overrideFilename
                : filenameFromUri(uri);
        String contentType = resp.headers().firstValue("content-type")
                .map(FileFetcher::stripCharset)
                .orElseGet(() -> contentTypeFor(filename));
        return new FetchedFile(filename, contentType, body);
    }

    public FetchedFile fromPath(String path, String overrideFilename) {
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            throw new IngestException("Path must be absolute: " + path);
        }
        if (!Files.isRegularFile(p)) {
            throw new IngestException("Not a regular file: " + path);
        }
        if (!Files.isReadable(p)) {
            throw new IngestException("File is not readable: " + path);
        }
        long size;
        byte[] bytes;
        try {
            size = Files.size(p);
            enforceSize(size, path);
            bytes = Files.readAllBytes(p);
        } catch (IOException e) {
            throw new IngestException("Failed to read " + path, e);
        }
        String filename = overrideFilename != null && !overrideFilename.isBlank()
                ? overrideFilename
                : p.getFileName().toString();
        return new FetchedFile(filename, contentTypeFor(filename), bytes);
    }

    public FetchedFile fromInline(String base64Content, String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IngestException("filename is required for inline source");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            throw new IngestException("source_value is not valid base64", e);
        }
        enforceSize(bytes.length, filename);
        return new FetchedFile(filename, contentTypeFor(filename), bytes);
    }

    private void enforceSize(long size, String origin) {
        if (size > maxFileBytes) {
            throw new IngestException("File " + origin + " is " + size
                    + " bytes; exceeds ingest.max-file-bytes=" + maxFileBytes);
        }
    }

    static String contentTypeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "application/octet-stream";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXT_CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    static String filenameFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return "download";
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isBlank() ? "download" : name;
    }

    static String stripCharset(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
    }
}
