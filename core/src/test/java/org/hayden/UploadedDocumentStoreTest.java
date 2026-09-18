package org.hayden;

import org.hayden.ingest.IngestException;
import org.hayden.ingest.InsufficientStorageException;
import org.hayden.ingest.UploadedDocumentStore;
import org.hayden.ingest.UploadedDocumentStore.Conflict;
import org.hayden.ingest.UploadedDocumentStore.ExpandedEntry;
import org.hayden.ingest.UploadedDocumentStore.ReplaceGuard;
import org.hayden.ingest.UploadedDocumentStore.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadedDocumentStoreTest {

    @TempDir
    Path base;

    private Path root;
    private Path tmp;
    private UploadedDocumentStore store;

    @BeforeEach
    void setUp() throws Exception {
        root = base.resolve("documents");
        tmp = root.resolve(".tmp");
        store = newStore(root, tmp);
    }

    // ---- kb_name validation (before any path is built) ----------------------

    @Test
    void kbName_withSeparator_dotDot_orEncodedSeparator_isRejectedBeforeAnyPathExists()
            throws Exception {
        for (String bad : new String[]{"a/b", "a\\b", "..", ".", "a%2Fb", "", "  ",
                ".hidden", "x".repeat(65)}) {
            assertThatThrownBy(() -> store.store(bad, null, "a.txt",
                    materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 1))
                    .isInstanceOf(IngestException.class)
                    .hasMessageContaining("kb_name");
        }
        // Nothing leaked into the root: only the .tmp dir (plus the temp
        // sources this test materialized into it) exists.
        try (var children = Files.list(root)) {
            assertThat(children.map(p -> p.getFileName().toString()))
                    .containsExactly(".tmp");
        }
    }

    // ---- filename / subdir sanitization -------------------------------------

    @Test
    void traversalAndControlChars_areStripped_extensionKept() throws Exception {
        StoredFile sf = store.store("docs", null, "../..\\..//evil.txt",
                materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 1);

        assertThat(sf.filename()).isEqualTo("evil.txt");
        assertThat(sf.path()).isEqualTo(root.resolve("docs").resolve("evil.txt"));
        assertThat(Files.readString(sf.path())).isEqualTo("x");
    }

    @Test
    void overlongFilename_isCapped_withExtensionPreserved() throws Exception {
        String longName = "a".repeat(300) + ".txt";
        StoredFile sf = store.store("docs", null, longName,
                materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 1);

        assertThat(sf.filename()).hasSize(200).endsWith(".txt");
    }

    @Test
    void emptyFilename_fallsBackToOrdinal() throws Exception {
        StoredFile sf = store.store("docs", null, "../..",
                materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 7);

        assertThat(sf.filename()).isEqualTo("upload-7");
    }

    @Test
    void subdir_isConfined_dotDotSegmentsStripped_absoluteRejected() throws Exception {
        StoredFile sf = store.store("docs", "manuals/../../2026", "a.txt",
                materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 1);
        // ".." segments vanish; the survivors nest under <root>/<kb>/.
        assertThat(sf.path()).isEqualTo(
                root.resolve("docs").resolve("manuals").resolve("2026").resolve("a.txt"));

        assertThatThrownBy(() -> store.store("docs", "/abs", "a.txt",
                materialize("x"), Conflict.REPLACE, ReplaceGuard.NONE, 1))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("relative");
    }

    // ---- conflict handling --------------------------------------------------

    @Test
    void onConflict_replace_overwritesInPlace() throws Exception {
        store.store("docs", null, "a.txt", materialize("old"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1);
        StoredFile sf = store.store("docs", null, "a.txt", materialize("new"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1);

        assertThat(sf.path()).isEqualTo(root.resolve("docs").resolve("a.txt"));
        assertThat(Files.readString(sf.path())).isEqualTo("new");
    }

    @Test
    void onConflict_suffix_storesNumberedSiblings() throws Exception {
        store.store("docs", null, "a.txt", materialize("1"),
                Conflict.SUFFIX, ReplaceGuard.NONE, 1);
        StoredFile second = store.store("docs", null, "a.txt", materialize("2"),
                Conflict.SUFFIX, ReplaceGuard.NONE, 1);
        StoredFile third = store.store("docs", null, "a.txt", materialize("3"),
                Conflict.SUFFIX, ReplaceGuard.NONE, 1);

        assertThat(second.filename()).isEqualTo("a-2.txt");
        assertThat(third.filename()).isEqualTo("a-3.txt");
        assertThat(Files.readString(root.resolve("docs").resolve("a.txt"))).isEqualTo("1");
    }

    @Test
    void onConflict_reject_throws_andKeepsExisting() throws Exception {
        store.store("docs", null, "a.txt", materialize("keep"),
                Conflict.REJECT, ReplaceGuard.NONE, 1);

        assertThatThrownBy(() -> store.store("docs", null, "a.txt", materialize("no"),
                Conflict.REJECT, ReplaceGuard.NONE, 1))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("on_conflict=reject");
        assertThat(Files.readString(root.resolve("docs").resolve("a.txt"))).isEqualTo("keep");
    }

    // ---- durable write mechanics --------------------------------------------

    @Test
    void successfulStore_leavesNoPartFile() throws Exception {
        store.store("docs", null, "a.txt", materialize("x"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1);

        try (var walk = Files.walk(root.resolve("docs"))) {
            assertThat(walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString()))
                    .containsExactly("a.txt");
        }
    }

    @Test
    void failedRename_removesHiddenPartFile() throws Exception {
        // A directory squatting on the target name makes the atomic rename
        // fail after the .part was written — the .part must not leak.
        Files.createDirectories(root.resolve("docs").resolve("a.txt"));

        assertThatThrownBy(() -> store.store("docs", null, "a.txt", materialize("x"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1))
                .isInstanceOf(IngestException.class);
        assertThat(Files.exists(root.resolve("docs").resolve(".a.txt.part"))).isFalse();
    }

    @Test
    void freeSpaceGuard_rejectsBeforeWriting() throws Exception {
        setField(store, "minFreeBytes", Long.MAX_VALUE / 2);

        assertThatThrownBy(() -> store.store("docs", null, "a.txt", materialize("x"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1))
                .isInstanceOf(InsufficientStorageException.class);
        assertThat(Files.exists(root.resolve("docs").resolve("a.txt"))).isFalse();
    }

    // ---- PDF magic check ----------------------------------------------------

    @Test
    void pdfExtensionWithoutPdfHeader_isRejected() throws Exception {
        assertThatThrownBy(() -> store.store("docs", null, "fake.pdf",
                materialize("hello world"), Conflict.REPLACE, ReplaceGuard.NONE, 1))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("%PDF-");
        assertThat(Files.exists(root.resolve("docs").resolve("fake.pdf"))).isFalse();

        StoredFile real = store.store("docs", null, "real.pdf",
                materialize("%PDF-1.4 minimal"), Conflict.REPLACE, ReplaceGuard.NONE, 1);
        assertThat(Files.exists(real.path())).isTrue();
    }

    // ---- ZIP expansion ------------------------------------------------------

    @Test
    void zipExpansion_preservesInternalStructure_andSkipsHiddenAndUnsupported()
            throws Exception {
        Path zip = makeZip(
                entry("docs/a.txt", "alpha"),
                entry("docs/sub/b.md", "# beta"),
                entry(".hidden/c.txt", "nope"),
                entry("docs/.dotfile.txt", "nope"),
                entry("docs/d.bin", "nope"),
                dirEntry("docs/sub/"));

        List<ExpandedEntry> out = store.expandZip("docs", "drop", zip,
                Conflict.REPLACE, ReplaceGuard.NONE);

        assertThat(out).allMatch(e -> e.error() == null);
        assertThat(out).extracting(e -> e.stored().path().toString())
                .containsExactly(
                        root.resolve("docs/drop/docs/a.txt").toString(),
                        root.resolve("docs/drop/docs/sub/b.md").toString());
        assertThat(Files.readString(root.resolve("docs/drop/docs/sub/b.md")))
                .isEqualTo("# beta");
    }

    @Test
    void zipSlip_failsTheWholeArchive() throws Exception {
        Path zip = makeZip(entry("../evil.txt", "boom"));

        assertThatThrownBy(() -> store.expandZip("docs", null, zip,
                Conflict.REPLACE, ReplaceGuard.NONE))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("zip-slip");
    }

    @Test
    void zipEntryCountCap_isEnforced() throws Exception {
        setField(store, "zipMaxEntries", 1);
        Path zip = makeZip(entry("a.txt", "1"), entry("b.txt", "2"));

        assertThatThrownBy(() -> store.expandZip("docs", null, zip,
                Conflict.REPLACE, ReplaceGuard.NONE))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("max_entries");
    }

    @Test
    void zipUncompressedByteCap_isEnforced() throws Exception {
        setField(store, "zipMaxUncompressedBytes", 8L);
        Path zip = makeZip(entry("a.txt", "way more than eight bytes of text"));

        assertThatThrownBy(() -> store.expandZip("docs", null, zip,
                Conflict.REPLACE, ReplaceGuard.NONE))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("max_uncompressed_bytes");
    }

    // ---- delete + sweep -----------------------------------------------------

    @Test
    void deleteStored_refusesPathsOutsideTheRoot() throws Exception {
        Path outside = base.resolve("elsewhere.txt");
        Files.writeString(outside, "keep me");

        assertThat(store.deleteStored(outside)).isFalse();
        assertThat(Files.exists(outside)).isTrue();

        StoredFile sf = store.store("docs", null, "a.txt", materialize("x"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1);
        assertThat(store.deleteStored(sf.path())).isTrue();
        assertThat(Files.exists(sf.path())).isFalse();
    }

    @Test
    void sweep_removesAgedStrays_leavesFreshOnes() throws Exception {
        Path agedPart = root.resolve("docs").resolve(".dead.pdf.part");
        Path freshPart = root.resolve("docs").resolve(".live.pdf.part");
        Path agedTmp = tmp.resolve("abandoned-body");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(agedPart, "x");
        Files.writeString(freshPart, "x");
        Files.writeString(agedTmp, "x");
        FileTime old = FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS));
        Files.setLastModifiedTime(agedPart, old);
        Files.setLastModifiedTime(agedTmp, old);

        store.sweepStrays();

        assertThat(Files.exists(agedPart)).isFalse();
        assertThat(Files.exists(agedTmp)).isFalse();
        assertThat(Files.exists(freshPart)).isTrue();
    }

    @Test
    void findByDocId_locatesStoredFile_byDeterministicId() throws Exception {
        StoredFile sf = store.store("docs", "sub", "a.txt", materialize("x"),
                Conflict.REPLACE, ReplaceGuard.NONE, 1);
        String docId = org.hayden.backend.qdrant.UuidV5.forSource("docs",
                org.hayden.ingest.DirectoryIngestService.canonicalSourcePath(sf.path()));

        assertThat(store.findByDocId("docs", docId)).contains(
                sf.path().toAbsolutePath().normalize());
        assertThat(store.findByDocId("docs", "not-a-real-id")).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------

    /** Simulates a materialized multipart temp file (same FileStore as root). */
    private Path materialize(String content) throws IOException {
        Path p = Files.createTempFile(tmp, "upload-", ".body");
        Files.writeString(p, content);
        return p;
    }

    private record ZipSpec(String name, String content, boolean dir) {
    }

    private static ZipSpec entry(String name, String content) {
        return new ZipSpec(name, content, false);
    }

    private static ZipSpec dirEntry(String name) {
        return new ZipSpec(name, "", true);
    }

    private Path makeZip(ZipSpec... entries) throws IOException {
        Path zip = Files.createTempFile(tmp, "batch-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (ZipSpec e : entries) {
                out.putNextEntry(new ZipEntry(e.name()));
                if (!e.dir()) {
                    out.write(e.content().getBytes());
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    static UploadedDocumentStore newStore(Path root, Path tmp) throws Exception {
        UploadedDocumentStore s = new UploadedDocumentStore();
        setField(s, "rootPath", root.toString());
        setField(s, "tmpDirPath", tmp.toString());
        setField(s, "fsync", false);   // durability is pointless on a test tmpdir
        setField(s, "minFreeBytes", 0L);
        setField(s, "tmpSweepMinutes", 60L);
        setField(s, "requireMount", false);
        setField(s, "zipMaxEntries", 500);
        setField(s, "zipMaxUncompressedBytes", 2147483648L);
        var init = UploadedDocumentStore.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(s);
        return s;
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
