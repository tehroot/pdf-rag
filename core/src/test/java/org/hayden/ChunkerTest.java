package org.hayden;

import org.hayden.backend.qdrant.Chunk;
import org.hayden.backend.qdrant.Chunker;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.PageText;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    @Test
    void shortText_returnsSingleChunk() throws Exception {
        Chunker c = chunker(100, 10);
        List<Chunk> chunks = c.chunk("hello world");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("hello world");
        assertThat(chunks.get(0).index()).isZero();
    }

    @Test
    void longText_breaksAtParagraphBoundaryWhenAvailable() throws Exception {
        Chunker c = chunker(50, 5);
        String para1 = "a".repeat(30);
        String para2 = "b".repeat(30);
        String text = para1 + "\n\n" + para2;

        List<Chunk> chunks = c.chunk(text);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).text()).endsWith("\n\n");
        assertThat(chunks.get(0).text()).contains(para1);
    }

    @Test
    void longText_breaksAtSentenceWhenNoParagraph() throws Exception {
        Chunker c = chunker(60, 5);
        // Two ". " boundaries within the back half of the first 60-char window so the
        // chunker prefers sentence break over word break.
        String text = "First. Second sentence here is medium. Third also. Fourth and final sentence at the end.";

        List<Chunk> chunks = c.chunk(text);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).text()).endsWith(". ");
    }

    @Test
    void overlapBytesShared_acrossAdjacentChunks() throws Exception {
        Chunker c = chunker(50, 10);
        // No paragraph/sentence boundary, so chunker falls back to word break and applies overlap.
        String text = "word ".repeat(40);

        List<Chunk> chunks = c.chunk(text);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startOffset())
                    .isLessThanOrEqualTo(chunks.get(i - 1).endOffset());
            assertThat(chunks.get(i).startOffset())
                    .isGreaterThanOrEqualTo(chunks.get(i - 1).startOffset() + 1);
        }
    }

    @Test
    void chunkIndicesAreSequential() throws Exception {
        Chunker c = chunker(20, 3);
        List<Chunk> chunks = c.chunk("x".repeat(200));
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void offsetsCoverInput() throws Exception {
        Chunker c = chunker(40, 5);
        String text = "hello world foo bar baz quux ".repeat(5).trim();

        List<Chunk> chunks = c.chunk(text);

        assertThat(chunks.get(0).startOffset()).isZero();
        assertThat(chunks.get(chunks.size() - 1).endOffset()).isEqualTo(text.length());
    }

    @Test
    void emptyOrBlank_rejected() throws Exception {
        Chunker c = chunker(100, 10);
        assertThatThrownBy(() -> c.chunk("")).isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> c.chunk("   ")).isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> c.chunk(null)).isInstanceOf(IngestException.class);
    }

    @Test
    void overlapMustBeLessThanSize() throws Exception {
        Chunker c = chunker(50, 50);
        assertThatThrownBy(() -> c.chunk("anything")).isInstanceOf(IngestException.class);
    }

    // ---- chunkPerPage --------------------------------------------------------

    @Test
    void chunkPerPage_singlePage_tagsAsThatPage() throws Exception {
        Chunker c = chunker(100, 10);
        List<Chunk> chunks = c.chunkPerPage(List.of(new PageText(7, "hello world")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).pageStart()).isEqualTo(7);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(7);
    }

    @Test
    void chunkPerPage_smallChunksWithinPages_eachTaggedWithValidRange() throws Exception {
        Chunker c = chunker(30, 5);
        List<PageText> pages = List.of(
                new PageText(1, "page one content here."),
                new PageText(2, "page two content here."),
                new PageText(3, "page three content here."));

        List<Chunk> chunks = c.chunkPerPage(pages);

        assertThat(chunks).isNotEmpty();
        for (Chunk ch : chunks) {
            assertThat(ch.pageStart()).isBetween(1, 3);
            assertThat(ch.pageEnd()).isBetween(ch.pageStart(), 3);
        }
        // The chunk tags should cover the full input page range collectively.
        int minPage = chunks.stream().mapToInt(Chunk::pageStart).min().orElseThrow();
        int maxPage = chunks.stream().mapToInt(Chunk::pageEnd).max().orElseThrow();
        assertThat(minPage).isEqualTo(1);
        assertThat(maxPage).isEqualTo(3);
    }

    @Test
    void chunkPerPage_chunkStraddlingTwoPages_tagsRange() throws Exception {
        // Tight window forces the chunker to produce chunks that span the page break.
        Chunker c = chunker(40, 8);
        String pageA = "a".repeat(25);
        String pageB = "b".repeat(25);
        List<Chunk> chunks = c.chunkPerPage(List.of(
                new PageText(4, pageA),
                new PageText(5, pageB)));

        // At least one chunk should straddle: pageStart=4, pageEnd=5.
        boolean spans = chunks.stream()
                .anyMatch(ch -> ch.pageStart() == 4 && ch.pageEnd() == 5);
        assertThat(spans)
                .as("expected at least one chunk to span pages 4 and 5: " + chunks)
                .isTrue();
    }

    @Test
    void chunkPerPage_skipsEmptyPages() throws Exception {
        Chunker c = chunker(100, 10);
        // Pages 2 and 4 are empty; their page numbers must NEVER appear as
        // pageStart or pageEnd on any chunk. The non-empty pages (1, 3, 5)
        // should be the only ones referenced.
        List<PageText> pages = List.of(
                new PageText(1, "first page content."),
                new PageText(2, ""),
                new PageText(3, "third page content."),
                new PageText(4, "   "),
                new PageText(5, "fifth page content."));

        List<Chunk> chunks = c.chunkPerPage(pages);

        for (Chunk ch : chunks) {
            assertThat(ch.pageStart()).isIn(1, 3, 5);
            assertThat(ch.pageEnd()).isIn(1, 3, 5);
        }
        // The non-empty pages collectively span the chunk tags.
        int minPage = chunks.stream().mapToInt(Chunk::pageStart).min().orElseThrow();
        int maxPage = chunks.stream().mapToInt(Chunk::pageEnd).max().orElseThrow();
        assertThat(minPage).isEqualTo(1);
        assertThat(maxPage).isEqualTo(5);
    }

    @Test
    void chunkPerPage_allEmpty_throws() throws Exception {
        Chunker c = chunker(100, 10);
        assertThatThrownBy(() -> c.chunkPerPage(List.of(
                new PageText(1, ""),
                new PageText(2, "   "))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void chunkPerPage_nullOrEmptyList_throws() throws Exception {
        Chunker c = chunker(100, 10);
        assertThatThrownBy(() -> c.chunkPerPage(null)).isInstanceOf(IngestException.class);
        assertThatThrownBy(() -> c.chunkPerPage(List.of())).isInstanceOf(IngestException.class);
    }

    @Test
    void chunkPerPage_preservesIndexOrder() throws Exception {
        Chunker c = chunker(30, 5);
        List<Chunk> chunks = c.chunkPerPage(List.of(
                new PageText(1, "long content that will need to be chunked into several pieces."),
                new PageText(2, "more content on the second page that should also be chunked.")));

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void chunkPerPage_nonContiguousPageNumbers_respected() throws Exception {
        // Sparse page numbers (some pages skipped or filtered upstream).
        Chunker c = chunker(50, 10);
        List<Chunk> chunks = c.chunkPerPage(List.of(
                new PageText(1, "Alpha content."),
                new PageText(7, "Beta content."),
                new PageText(42, "Gamma content.")));

        assertThat(chunks).extracting(Chunk::pageStart)
                .containsAnyOf(1, 7, 42);
        assertThat(chunks).allSatisfy(ch ->
                assertThat(ch.pageStart()).isIn(1, 7, 42));
    }

    @Test
    void chunkPerPage_shortConcatenation_returnsSingleChunkWithFullRange() throws Exception {
        // Two short pages whose concatenation fits in one chunk.
        Chunker c = chunker(200, 20);
        List<Chunk> chunks = c.chunkPerPage(List.of(
                new PageText(3, "short"),
                new PageText(4, "also short")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).pageStart()).isEqualTo(3);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(4);
    }

    @Test
    void chunk_backCompat_defaultPageTagsAreOne() throws Exception {
        Chunker c = chunker(100, 10);
        List<Chunk> chunks = c.chunk("plain text input");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(1);
    }

    private static Chunker chunker(int size, int overlap) throws Exception {
        Chunker c = new Chunker();
        Field s = Chunker.class.getDeclaredField("sizeChars");
        s.setAccessible(true);
        s.setInt(c, size);
        Field o = Chunker.class.getDeclaredField("overlapChars");
        o.setAccessible(true);
        o.setInt(c, overlap);
        return c;
    }
}
