package org.example;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.example.ingest.FetchedFile;
import org.example.ingest.FileFetcher;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileFetcherTest {

    private WireMockServer server;
    private FileFetcher fetcher;

    @BeforeEach
    void start() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        fetcher = newFetcher();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void fromUrl_returnsBytesAndInfersContentType() {
        byte[] payload = "%PDF-1.4 mock".getBytes();
        server.stubFor(get(urlEqualTo("/docs/sample.pdf"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(payload)));

        FetchedFile f = fetcher.fromUrl(server.baseUrl() + "/docs/sample.pdf", null);

        assertThat(f.filename()).isEqualTo("sample.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThat(f.content()).isEqualTo(payload);
    }

    @Test
    void fromUrl_stripsCharsetFromContentType() {
        server.stubFor(get(urlEqualTo("/hello.txt"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=utf-8")
                        .withBody("hi")));

        FetchedFile f = fetcher.fromUrl(server.baseUrl() + "/hello.txt", null);

        assertThat(f.contentType()).isEqualTo("text/plain");
    }

    @Test
    void fromUrl_overrideFilenameWins() {
        server.stubFor(get(urlEqualTo("/raw"))
                .willReturn(aResponse().withStatus(200).withBody("data")));

        FetchedFile f = fetcher.fromUrl(server.baseUrl() + "/raw", "renamed.pdf");

        assertThat(f.filename()).isEqualTo("renamed.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void fromUrl_non2xxFails() {
        server.stubFor(get(urlEqualTo("/missing"))
                .willReturn(aResponse().withStatus(404).withBody("nope")));

        assertThatThrownBy(() -> fetcher.fromUrl(server.baseUrl() + "/missing", null))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void fromUrl_rejectsNonHttpScheme() {
        assertThatThrownBy(() -> fetcher.fromUrl("file:///etc/passwd", null))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("Only http and https");
    }

    @Test
    void fromPath_readsLocalFile() throws Exception {
        Path tmp = Files.createTempFile("ingest-test-", ".pdf");
        Files.writeString(tmp, "hello pdf");
        try {
            FetchedFile f = fetcher.fromPath(tmp.toAbsolutePath().toString(), null);
            assertThat(f.filename()).isEqualTo(tmp.getFileName().toString());
            assertThat(f.contentType()).isEqualTo("application/pdf");
            assertThat(new String(f.content())).isEqualTo("hello pdf");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void fromPath_requiresAbsolutePath() {
        assertThatThrownBy(() -> fetcher.fromPath("relative/path.pdf", null))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void fromInline_decodesBase64() {
        String b64 = Base64.getEncoder().encodeToString("inline bytes".getBytes());
        FetchedFile f = fetcher.fromInline(b64, "doc.txt");
        assertThat(new String(f.content())).isEqualTo("inline bytes");
        assertThat(f.contentType()).isEqualTo("text/plain");
    }

    @Test
    void fromInline_requiresFilename() {
        assertThatThrownBy(() -> fetcher.fromInline("AAAA", null))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("filename");
    }

    @Test
    void fromInline_rejectsBadBase64() {
        assertThatThrownBy(() -> fetcher.fromInline("!!!not-base64!!!", "x.txt"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void fromInline_enforcesMaxSize() throws Exception {
        FileFetcher tiny = newFetcher();
        setField(tiny, "maxFileBytes", 4L);

        String tooBig = Base64.getEncoder().encodeToString("12345".getBytes());
        assertThatThrownBy(() -> tiny.fromInline(tooBig, "x.txt"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("exceeds");
    }

    private static FileFetcher newFetcher() throws Exception {
        FileFetcher f = new FileFetcher();
        setField(f, "maxFileBytes", 100L * 1024 * 1024);
        setField(f, "connectTimeoutSeconds", 5L);
        setField(f, "requestTimeoutSeconds", 10L);
        // Reflectively invoke @PostConstruct to set up the HttpClient.
        var init = f.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(f);
        return f;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
