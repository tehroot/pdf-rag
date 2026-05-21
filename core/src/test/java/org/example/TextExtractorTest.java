package org.example;

import org.example.backend.qdrant.TextExtractor;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tika smoke tests. PDF parsing is exercised by the live smoke-test path documented
 * in the deployment docs; here we cover plain text and HTML, which is enough to
 * verify the wiring (AutoDetectParser + metadata-driven content-type hints).
 */
class TextExtractorTest {

    @Test
    void plainText_extractsAsIs() throws Exception {
        TextExtractor x = newExtractor();
        FetchedFile f = new FetchedFile("doc.txt", "text/plain",
                "hello world\nsecond line".getBytes());

        String text = x.extract(f);

        assertThat(text).contains("hello world");
        assertThat(text).contains("second line");
    }

    @Test
    void html_stripsTagsKeepsText() throws Exception {
        TextExtractor x = newExtractor();
        String html = "<html><body><h1>Title</h1><p>Some body text.</p></body></html>";
        FetchedFile f = new FetchedFile("page.html", "text/html", html.getBytes());

        String text = x.extract(f);

        assertThat(text).contains("Title");
        assertThat(text).contains("Some body text.");
        assertThat(text).doesNotContain("<h1>");
        assertThat(text).doesNotContain("<body>");
    }

    @Test
    void emptyInput_throws() throws Exception {
        TextExtractor x = newExtractor();
        FetchedFile f = new FetchedFile("empty.txt", "text/plain", "".getBytes());
        assertThatThrownBy(() -> x.extract(f)).isInstanceOf(IngestException.class);
    }

    private static TextExtractor newExtractor() throws Exception {
        TextExtractor x = new TextExtractor();
        Field f = TextExtractor.class.getDeclaredField("maxChars");
        f.setAccessible(true);
        f.setInt(x, 1_000_000);
        return x;
    }
}
