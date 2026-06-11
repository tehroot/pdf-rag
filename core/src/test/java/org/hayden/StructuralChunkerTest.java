package org.hayden;

import org.hayden.backend.qdrant.Block;
import org.hayden.backend.qdrant.Chunk;
import org.hayden.backend.qdrant.Chunker;
import org.hayden.backend.qdrant.StructuralChunker;
import org.hayden.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralChunkerTest {

    @Test
    void packsConsecutiveBlocksUpToSize() throws Exception {
        StructuralChunker c = chunker(100, 120);
        List<Block> blocks = List.of(
                para("aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa", 1),  // 39 chars
                para("bbbb bbbb bbbb bbbb bbbb bbbb bbbb bbbb", 1),  // fits with first (39+2+39=80)
                para("cccc cccc cccc cccc cccc cccc cccc cccc", 1)); // would exceed 100 → new chunk

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).contains("aaaa").contains("bbbb");
        assertThat(out.get(1).text()).contains("cccc");
    }

    @Test
    void chunkIndexesSequential_andCharRangesDisjoint() throws Exception {
        StructuralChunker c = chunker(50, 120);
        List<Block> blocks = List.of(
                para("first paragraph with enough text to fill one", 1),
                para("second paragraph with enough text to fill one", 1),
                para("third paragraph with enough text to fill one", 2));

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out.size()).isGreaterThanOrEqualTo(3);
        for (int i = 0; i < out.size(); i++) {
            assertThat(out.get(i).index()).isEqualTo(i);
            assertThat(out.get(i).endOffset()).isGreaterThan(out.get(i).startOffset());
            if (i > 0) {
                assertThat(out.get(i).startOffset())
                        .isGreaterThan(out.get(i - 1).endOffset());
            }
        }
    }

    @Test
    void tableBlock_neverSplitsAcrossChunks() throws Exception {
        StructuralChunker c = chunker(80, 120);
        String table = "h1\th2\th3\nr1a\tr1b\tr1c\nr2a\tr2b\tr2c";
        List<Block> blocks = List.of(
                para("a paragraph that takes up most of the chunk budget already", 1),
                new Block(Block.BlockType.TABLE, table, 1, List.of()));

        List<Chunk> out = c.chunkBlocks(blocks);

        // The table doesn't fit alongside the paragraph → it gets its own
        // chunk, whole.
        assertThat(out).hasSize(2);
        assertThat(out.get(1).text()).isEqualTo(table);
    }

    @Test
    void oversizeBlock_fallsBackToSlidingWindow() throws Exception {
        StructuralChunker c = chunker(60, 120);
        String big = "alpha beta gamma delta. ".repeat(10).strip(); // ~240 chars
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, big, 3, List.of("Section")));

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out.size()).isGreaterThan(1);
        for (Chunk chunk : out) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(60);
            assertThat(chunk.pageStart()).isEqualTo(3);
            assertThat(chunk.pageEnd()).isEqualTo(3);
            assertThat(chunk.headingPath()).containsExactly("Section");
        }
    }

    @Test
    void breadcrumb_inEmbeddingTextOnly() throws Exception {
        StructuralChunker c = chunker(500, 120);
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, "fan curves are configured here",
                        7, List.of("Install", "Cooling")));

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out).hasSize(1);
        Chunk chunk = out.get(0);
        assertThat(chunk.text()).isEqualTo("fan curves are configured here");
        assertThat(chunk.embeddingText())
                .isEqualTo("Install > Cooling\nfan curves are configured here");
        assertThat(chunk.headingPath()).containsExactly("Install", "Cooling");
    }

    @Test
    void noHeadingContext_embeddingTextEqualsText() throws Exception {
        StructuralChunker c = chunker(500, 120);
        List<Block> blocks = List.of(para("plain text", 1));

        Chunk chunk = c.chunkBlocks(blocks).get(0);

        assertThat(chunk.embeddingText()).isEqualTo(chunk.text());
        assertThat(chunk.headingPath()).isNull();
    }

    @Test
    void breadcrumb_truncationDropsOutermostSegments() throws Exception {
        StructuralChunker c = chunker(500, 30);
        List<Block> blocks = List.of(
                new Block(Block.BlockType.PARAGRAPH, "body",
                        1, List.of("Very Long Outer Section Name", "Mid", "Inner")));

        Chunk chunk = c.chunkBlocks(blocks).get(0);

        String crumb = chunk.embeddingText().lines().findFirst().orElseThrow();
        assertThat(crumb.length()).isLessThanOrEqualTo(30);
        assertThat(crumb).startsWith("…").contains("Inner");
    }

    @Test
    void chunkSpanningPages_carriesPageRange() throws Exception {
        StructuralChunker c = chunker(200, 120);
        List<Block> blocks = List.of(
                para("text on page three", 3),
                para("continued on page four", 4));

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pageStart()).isEqualTo(3);
        assertThat(out.get(0).pageEnd()).isEqualTo(4);
    }

    @Test
    void headingBoundary_flushesWhenBufferNonTriviallyFull() throws Exception {
        StructuralChunker c = chunker(100, 120);
        List<Block> blocks = List.of(
                para("forty-plus chars of content under section A here", 1), // > 40% of 100
                new Block(Block.BlockType.HEADING, "Section B", 1, List.of("Section B")),
                new Block(Block.BlockType.PARAGRAPH, "content under B", 1, List.of("Section B")));

        List<Chunk> out = c.chunkBlocks(blocks);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).contains("section A");
        assertThat(out.get(1).text()).isEqualTo("content under B");
        assertThat(out.get(1).embeddingText()).startsWith("Section B\n");
    }

    @Test
    void emptyBlockList_throws() throws Exception {
        StructuralChunker c = chunker(100, 120);
        assertThatThrownBy(() -> c.chunkBlocks(List.of()))
                .isInstanceOf(IngestException.class);
    }

    private static Block para(String text, int page) {
        return new Block(Block.BlockType.PARAGRAPH, text, page, List.of());
    }

    private static StructuralChunker chunker(int sizeChars, int breadcrumbMax) throws Exception {
        Chunker sliding = new Chunker();
        setField(sliding, "sizeChars", sizeChars);
        setField(sliding, "overlapChars", Math.min(10, sizeChars / 4));

        StructuralChunker c = new StructuralChunker();
        setField(c, "sizeChars", sizeChars);
        setField(c, "breadcrumbMaxChars", breadcrumbMax);
        setField(c, "slidingFallback", sliding);
        return c;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
