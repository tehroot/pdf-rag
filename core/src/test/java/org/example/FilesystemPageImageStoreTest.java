package org.example;

import org.example.backend.qdrant.FilesystemPageImageStore;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemPageImageStoreTest {

    private Path tmpRoot;
    private FilesystemPageImageStore store;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("pageimg-test-");
        store = newStore(tmpRoot.toString(), "filesystem");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            try (Stream<Path> walk = Files.walk(tmpRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void store_writesPngBytesAtExpectedPath() throws Exception {
        byte[] png = "fake-png-bytes".getBytes();
        String key = store.store("docs", "doc-uuid-1", 7, png);

        assertThat(key).isEqualTo("docs/doc-uuid-1/000007.png");
        Path expectedFile = tmpRoot.resolve(key);
        assertThat(Files.exists(expectedFile)).isTrue();
        assertThat(Files.readAllBytes(expectedFile)).isEqualTo(png);
    }

    @Test
    void retrieve_roundTrip() throws Exception {
        byte[] png = "round-trip-png".getBytes();
        String key = store.store("docs", "doc-1", 1, png);

        byte[] retrieved = store.retrieve(key);
        assertThat(retrieved).isEqualTo(png);
    }

    @Test
    void retrieve_missingKey_returnsNull() {
        assertThat(store.retrieve("docs/missing-doc/000001.png")).isNull();
        assertThat(store.retrieve(null)).isNull();
        assertThat(store.retrieve("")).isNull();
    }

    @Test
    void store_overwritesExisting() throws Exception {
        store.store("docs", "doc-1", 1, "v1".getBytes());
        store.store("docs", "doc-1", 1, "v2".getBytes());
        assertThat(store.retrieve("docs/doc-1/000001.png"))
                .isEqualTo("v2".getBytes());
    }

    @Test
    void deleteForDoc_removesAllDocPages() throws Exception {
        store.store("docs", "doc-1", 1, "p1".getBytes());
        store.store("docs", "doc-1", 2, "p2".getBytes());
        store.store("docs", "doc-1", 3, "p3".getBytes());
        store.store("docs", "doc-2", 1, "other".getBytes());

        int removed = store.deleteForDoc("docs", "doc-1");

        assertThat(removed).isEqualTo(3);
        assertThat(store.retrieve("docs/doc-1/000001.png")).isNull();
        assertThat(store.retrieve("docs/doc-1/000002.png")).isNull();
        assertThat(store.retrieve("docs/doc-1/000003.png")).isNull();
        // The unrelated doc survives.
        assertThat(store.retrieve("docs/doc-2/000001.png")).isEqualTo("other".getBytes());
    }

    @Test
    void deleteForKb_removesAllDocsInKb() throws Exception {
        store.store("docs", "d1", 1, "a".getBytes());
        store.store("docs", "d2", 1, "b".getBytes());
        store.store("docs", "d2", 2, "c".getBytes());
        store.store("other", "d3", 1, "z".getBytes());

        int removed = store.deleteForKb("docs");

        assertThat(removed).isEqualTo(3);
        assertThat(store.retrieve("docs/d1/000001.png")).isNull();
        assertThat(store.retrieve("docs/d2/000001.png")).isNull();
        assertThat(store.retrieve("docs/d2/000002.png")).isNull();
        // Other KB untouched.
        assertThat(store.retrieve("other/d3/000001.png")).isEqualTo("z".getBytes());
    }

    @Test
    void deleteForKb_missingKb_returnsZero() {
        assertThat(store.deleteForKb("does-not-exist")).isZero();
    }

    @Test
    void store_emptyPng_throws() {
        assertThatThrownBy(() -> store.store("docs", "d1", 1, new byte[0]))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("empty PNG");
        assertThatThrownBy(() -> store.store("docs", "d1", 1, null))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void store_invalidKbName_throws() {
        byte[] png = "x".getBytes();
        assertThatThrownBy(() -> store.store("../escape", "d1", 1, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store("kb/with/slash", "d1", 1, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store(".", "d1", 1, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store("", "d1", 1, png))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void store_invalidDocId_throws() {
        byte[] png = "x".getBytes();
        assertThatThrownBy(() -> store.store("docs", "../escape", 1, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store("docs", "doc/with/slash", 1, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store("docs", "", 1, png))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void store_invalidPageNumber_throws() {
        byte[] png = "x".getBytes();
        assertThatThrownBy(() -> store.store("docs", "d1", 0, png))
                .isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> store.store("docs", "d1", -5, png))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void retrieve_pathTraversalKey_returnsNull() throws Exception {
        // Attempt to escape root via a crafted key.
        assertThat(store.retrieve("../../etc/passwd")).isNull();
        assertThat(store.retrieve("/etc/passwd")).isNull();
    }

    @Test
    void unsupportedImpl_throwsAtInit() {
        assertThatThrownBy(() -> newStore(tmpRoot.toString(), "s3"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("s3");
    }

    @Test
    void backendName_returnsFilesystem() {
        assertThat(store.backendName()).isEqualTo("filesystem");
    }

    @Test
    void zeroPaddedSixDigit_pageNumbers() throws Exception {
        store.store("docs", "d1", 1, "p".getBytes());
        store.store("docs", "d1", 1234, "p".getBytes());
        assertThat(Files.exists(tmpRoot.resolve("docs/d1/000001.png"))).isTrue();
        assertThat(Files.exists(tmpRoot.resolve("docs/d1/001234.png"))).isTrue();
    }

    private static FilesystemPageImageStore newStore(String root, String impl) throws Exception {
        FilesystemPageImageStore s = new FilesystemPageImageStore();
        Field i = FilesystemPageImageStore.class.getDeclaredField("configuredImpl");
        i.setAccessible(true);
        i.set(s, impl);
        Field r = FilesystemPageImageStore.class.getDeclaredField("rootPath");
        r.setAccessible(true);
        r.set(s, root);
        var init = FilesystemPageImageStore.class.getDeclaredMethod("init");
        init.setAccessible(true);
        try {
            init.invoke(s);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // Unwrap so tests can assert on the real exception type thrown by init().
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw ex;
        }
        return s;
    }
}
