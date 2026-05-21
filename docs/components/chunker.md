# Chunker

`core/src/main/java/org/hayden/backend/qdrant/Chunker.java` (~180 lines). The
second step of the Qdrant pipeline — splits long extracted text into bounded,
overlapping chunks suitable for embedding. **Each chunk carries the page
range it came from**, which is what makes the chunk-to-page join in fusion
work.

Pure logic, no I/O. Trivially testable and the most "tunable" piece of the
pipeline.

## What it does

A sliding-window chunker with three behaviors layered on top:

1. **Bounded window.** Each chunk is at most `ingest.chunk.size-chars` long
   (default 1500).
2. **Overlap.** Adjacent chunks share `ingest.chunk.overlap-chars` characters
   (default 200) — so a query that hits text near a chunk boundary still has a
   good chance of matching at least one chunk that contains the full
   surrounding context.
3. **Boundary preference.** Within the back half of each window, the chunker
   prefers to break at a paragraph boundary (`\n\n`), then a sentence (`. `),
   then a newline, then a word boundary. Only if none of those exist does it
   hard-cut at the window edge.

## Two entry points

- `chunk(String)` — single blob input. Each output chunk carries
  `pageStart = pageEnd = 1` (treats the input as one conceptual page).
  Used by callers that don't track page numbers (back-compat path).
- `chunkPerPage(List<PageText>)` — page-tagged input. Each output chunk
  carries `pageStart` and `pageEnd` representing the source page range it
  spans. **This is the path the real ingest uses** — `ChunkPipeline.ingestChunks`
  calls it with per-page text from `TextExtractor.extractPerPage`.

## Interface

```java
public record PageText(int pageNumber, String text);
public record Chunk(int index, int startOffset, int endOffset, String text,
                    int pageStart, int pageEnd) {
    // Back-compat constructor: pageStart = pageEnd = 1.
    public Chunk(int index, int startOffset, int endOffset, String text);
}

@ConfigProperty(name = "ingest.chunk.size-chars",    defaultValue = "1500") int sizeChars;
@ConfigProperty(name = "ingest.chunk.overlap-chars", defaultValue = "200")  int overlapChars;

public List<Chunk> chunk(String text);
```

Returns `Chunk` records that retain enough context to be useful downstream:

- `index` — sequential, starting from 0.
- `startOffset`, `endOffset` — character offsets in the (normalized) input.
  The Qdrant payload stores these as `char_start` / `char_end` so callers can
  reconstruct provenance after the fact.
- `text` — the actual chunk content.

## Internals

```
┌─────────────┐
│  norm text  │ length N (after collapsing 3+ newlines and trimming)
└─┬───────────┘
  │ start = 0
  ▼
┌──────────────────────────────────────────────────────────────┐
│                          window                              │  end = min(start + size, N)
│   ◄── first half ──►   ◄── back half (look for breaks) ──►   │
└──────────────────────────────────────────────────────────────┘
                          │
                          ▼ preferredBreak(text, start, end)
                          (latest \n\n, then ". ", then \n, then ' ')
                          ▼
                       chunk end
                          │
                          ▼ start = end - overlap (guarded forward)
                          ▶ next window
```

### Step 1: normalize

```java
String norm = text.replaceAll("\\n{3,}", "\n\n").trim();
```

Collapses runs of 3+ newlines to exactly two, then trims outer whitespace.
Single `\n` and double `\n\n` are preserved on purpose — paragraph signal
matters for the boundary preference, and Tika often produces both depending
on the source.

### Step 2: short-circuit

If `norm.length() <= sizeChars`, return a single chunk and stop. The most
common case (small inputs) avoids any loop at all.

### Step 3: sliding window

```java
int start = 0;
while (start < norm.length()) {
    int windowEnd = Math.min(start + sizeChars, norm.length());
    int end = (windowEnd == norm.length()) ? windowEnd : preferredBreak(norm, start, windowEnd);
    out.add(new Chunk(idx++, start, end, norm.substring(start, end)));
    if (end >= norm.length()) break;
    int next = end - overlapChars;
    start = (next <= start) ? end : next;   // forward-progress guard
}
```

The last window always ends at `norm.length()` — no overlap-after-end nonsense.

The **forward-progress guard** (`next <= start`) handles the pathological case
of a tiny chunk + big overlap: if subtracting `overlap` would push us back to
or before `start`, just continue from `end` (drop the overlap for this step).
Without this guard, a configuration like `sizeChars=50, overlapChars=49`
against a 200-char input with no break-points would loop forever advancing by
1 char at a time, or even regress.

### Step 4: boundary preference

```java
static int preferredBreak(String s, int start, int end) {
    int floor = start + (end - start) / 2;        // back half only
    int rel = lastIndexOf(s, "\n\n", floor, end);
    if (rel >= 0) return rel + 2;
    rel = lastIndexOf(s, ". ", floor, end);
    if (rel >= 0) return rel + 2;
    rel = s.lastIndexOf('\n', end - 1);
    if (rel >= floor) return rel + 1;
    rel = s.lastIndexOf(' ', end - 1);
    if (rel >= floor) return rel + 1;
    return end;
}
```

Search order (latest match wins):

1. `\n\n` — paragraph break.
2. `. ` — sentence break (English period followed by space; ", " before a
   capital would also work in English, but `". "` is a cleaner signal).
3. `\n` — line break.
4. `' '` — word break.

All searches are confined to the **back half** of the window (the `floor`).
This is the trade-off: we'd like to break at paragraph boundaries even if the
nearest one is early in the window, but doing so produces tiny chunks. By
requiring the break to be in the second half, we get chunks that are at least
half the configured size, even on adversarial inputs.

Falling through all four checks (no whitespace in the back half — extremely
long single word) hard-cuts at `end`.

### Step 5: overlap

After the chunk lands, `start = end - overlapChars`. The next chunk begins
that many characters into the current chunk's end region, so the same text
appears in two adjacent chunks. For a query that lands near a boundary
(say, "rate limiting" where "rate" is in chunk N and "limiting" is in chunk
N+1), at least one of the two chunks will contain the full phrase.

## Failure modes

| Case | Throws |
|------|--------|
| `text == null` or `text.isBlank()` | `IngestException("Cannot chunk empty text")` |
| `sizeChars <= 0` | `IngestException("ingest.chunk.size-chars must be > 0")` |
| `overlapChars < 0` or `overlapChars >= sizeChars` | `IngestException("ingest.chunk.overlap-chars must satisfy 0 <= overlap < size ...")` |

That's it. Within the valid config range, the chunker always terminates and
always produces ≥ 1 chunk.

## Why it's like this

- **Characters, not tokens.** A token-aware chunker (using the embedding
  model's tokenizer) would be marginally more accurate at hitting the model's
  context length, but it would couple this class to a specific tokenizer
  library and a specific model. Characters are model-agnostic and easy to reason
  about — 1500 chars ≈ 350-400 tokens for typical English, well under any
  modern embedding model's context limit.
- **Back-half-only boundary search.** Without the `floor`, the chunker could
  produce chunks of wildly varying sizes (sometimes 200 chars when a `\n\n`
  appears 200 chars in, sometimes 1500). The floor caps that variance — every
  chunk is at least `size/2` chars (so 750 by default), unless the input is
  shorter than that.
- **Paragraph > sentence > newline > word.** This ordering reflects what a
  human reader would naturally cut at. Falling through to single-space breaks
  is a graceful degradation for inputs without prose structure (e.g. logs,
  CSV-as-text).
- **Forward-progress guard.** Defensive against operator misconfiguration.
  Cheap line, prevents an infinite loop.
- **Overlap is bytes, not "carry the last sentence forward".** A
  sentence-aware overlap would be neater but doubles the complexity and saves
  maybe 50 chars on average. The fixed 200-char overlap is "good enough" for
  recall.

## Page-range mechanics in `chunkPerPage`

```java
public List<Chunk> chunkPerPage(List<PageText> pages) {
    // 1. Concatenate normalized per-page texts with "\n\n" separators;
    //    skip empty pages.
    // 2. Track each page's character range in the concatenation:
    //    pageRanges = [{pageNumber, charStart, charEnd}, ...]
    // 3. Run the standard chunking algorithm on the concatenated string.
    // 4. For each emitted chunk, look at its [startOffset, endOffset) range
    //    and find the FIRST and LAST page-range it overlaps.
    //    Those become pageStart and pageEnd on the Chunk.
}
```

A chunk that straddles a page boundary gets `pageEnd > pageStart`. Middle
pages (when a chunk spans 3+ pages because of a small chunker size +
big pages) are *implied* between pageStart and pageEnd — not separately
enumerated on the Chunk. Skipped-empty pages don't appear in any chunk's tag.

The page separator (`\n\n`) doubles as a paragraph break for the chunker's
boundary-preference logic — so chunks tend to split at page boundaries when
the configured chunk size makes that natural.

## Tests

`ChunkerTest` (18 tests):

- `shortText_returnsSingleChunk` — input under `sizeChars` → 1 chunk, no
  overlap math.
- `longText_breaksAtParagraphBoundaryWhenAvailable` — `\n\n` in the back half
  → chunk ends with `\n\n`.
- `longText_breaksAtSentenceWhenNoParagraph` — `. ` in the back half → chunk
  ends with `". "`.
- `overlapBytesShared_acrossAdjacentChunks` — adjacent chunks satisfy
  `chunk[N+1].start <= chunk[N].end` (overlap) and
  `chunk[N+1].start > chunk[N].start` (forward progress).
- `chunkIndicesAreSequential` — indices 0, 1, 2, …
- `offsetsCoverInput` — first chunk starts at 0; last chunk ends at input
  length.
- `emptyOrBlank_rejected` — `""`, `"   "`, `null` all throw.
- `overlapMustBeLessThanSize` — config-validation throw.

New for `chunkPerPage`:

- `chunkPerPage_singlePage_tagsAsThatPage` — one page → all chunks have
  `pageStart=pageEnd=that page`.
- `chunkPerPage_smallChunksWithinPages_eachTaggedWithValidRange` — multi-page
  input, all chunks tagged within `[1, 3]`, min/max spans the full input
  range.
- `chunkPerPage_chunkStraddlingTwoPages_tagsRange` — tight window forces
  chunks to span page boundaries; verifies `pageStart=4, pageEnd=5` on at
  least one chunk.
- `chunkPerPage_skipsEmptyPages` — pages 2 and 4 empty; chunks reference only
  pages 1, 3, 5.
- `chunkPerPage_allEmpty_throws` and `…_nullOrEmptyList_throws` — input validation.
- `chunkPerPage_preservesIndexOrder` — indices 0, 1, 2, … across the full output.
- `chunkPerPage_nonContiguousPageNumbers_respected` — pages 1, 7, 42 (sparse)
  → tags reference 1, 7, 42 only.
- `chunkPerPage_shortConcatenation_returnsSingleChunkWithFullRange` — two
  short pages concatenated fit in one chunk; that chunk spans pages 3-4.
- `chunk_backCompat_defaultPageTagsAreOne` — the single-blob `chunk(String)`
  method preserves the legacy `pageStart=pageEnd=1` semantics.

Tests construct the bean directly, set the two int fields via reflection,
and call the method. Pure logic, no setup needed; full suite runs in ~200 ms.
