# TextLayerProbe

`core/.../backend/qdrant/TextLayerProbe.java` (~100 lines). Fast PDFBox
text-stripping pass over each PDF page, classifying it on a 0-2 scale by how
much extractable text exists. Drives Tesseract-OCR gating decisions and the
`text_trust` weighting in `ConfidenceCalculator`.

## What it does

```java
public List<PageQuality> probe(FetchedFile file);
public int classify(int charCount);

public record PageQuality(int pageNumber, int charCount, int textQuality);
```

For each page:
1. Run PDFBox `PDFTextStripper` against just that page (no OCR).
2. Count extracted characters.
3. Bucket: `< thresholdLow` → 0; `< thresholdFull` → 1; otherwise → 2.

Non-PDF inputs are reported as a single page with `textQuality=2` (assuming
DOCX / HTML / text are inherently text-bearing once Tika extracts them).

## Why bucket text quality?

A PDF can be one of three things, and the agent has no way to tell from the
outside:

- **Born-digital** — Word / LaTeX / print-to-PDF. The actual character glyphs
  carry their text equivalents. PDFBox extraction is exact and free.
- **Scanned** — paper through a scanner. The PDF is just an image wrapped in
  a container. No text layer. Must OCR.
- **Hybrid** — scan with a pre-baked OCR layer. Quality varies wildly with
  whoever did the OCR.

The probe is a single ~10ms call per page that tells us which bucket we're
in. That signal feeds three decisions:

1. **OCR gating** (Tesseract). Pages with `text_quality=2` have clean text
   already; skip Tesseract entirely. Saves ~1-3 seconds per page when
   Tesseract is installed.
2. **Visual gating** (future). Could skip ColPali on `text_quality=2` pages to
   save sidecar work. Not enabled in v1 — visual runs on every page when the
   KB has it enabled — but the signal is in place.
3. **Confidence weighting** at query time. Each chunk's source-page
   `text_quality` becomes a `text_trust` factor (0.0 / 0.5 / 1.0). A
   high-cosine match in an OCR-garbled chunk doesn't dominate confidence.

## Interface

```java
@ApplicationScoped
public class TextLayerProbe {
    @ConfigProperty(name = "ingest.text_quality.threshold_low",  defaultValue = "50")
    int thresholdLow;
    @ConfigProperty(name = "ingest.text_quality.threshold_full", defaultValue = "500")
    int thresholdFull;

    public List<PageQuality> probe(FetchedFile file);
    public int classify(int charCount);
}
```

Defaults are guesses tuned to "typical English prose":
- 0: `< 50` chars (effectively no text layer)
- 1: `50-499` chars (partial — captions, sparse layout, hybrid OCR)
- 2: `>= 500` chars (full text layer)

Tunable via env (`INGEST_TEXT_QUALITY_THRESHOLD_LOW` / `_FULL`).

## Internals

```java
public List<PageQuality> probe(FetchedFile file) {
    validateThresholds();   // low <= full, both non-negative
    if (!isPdf(file)) {
        return List.of(new PageQuality(1, file.content().length, 2));
    }
    try (PDDocument doc = Loader.loadPDF(file.content())) {
        List<PageQuality> out = new ArrayList<>();
        for (int i = 1; i <= doc.getNumberOfPages(); i++) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(i);
            s.setEndPage(i);
            String text = s.getText(doc);
            int charCount = text == null ? 0 : text.length();
            out.add(new PageQuality(i, charCount, classify(charCount)));
        }
        return out;
    }
}
```

`PDFTextStripper` is stateless beyond the start/end page configuration —
constructing a fresh one per page is cheap. Per-page extraction is what we
want because text-quality varies within a document (a born-digital cover
page + scanned body pages is a real pattern).

## Failure modes

| Case | Result |
|------|--------|
| Non-PDF input | Returns a single `PageQuality(1, contentLength, 2)`. |
| PDF with 0 pages | `IngestException("PDF X has 0 pages")`. |
| `thresholdLow > thresholdFull` | `IngestException("threshold_low must be <= threshold_full")`. |
| Negative thresholds | `IngestException("thresholds must be non-negative")`. |
| PDFBox I/O failure | `IngestException("Failed to probe text layer ...", IOException)`. |

## Why it's like this

- **Separate from `TextExtractor`.** Two different concerns: the probe
  classifies; the extractor produces text the chunker can use. Could share
  one PDFBox pass to save work, but separation lets us:
  - Probe independently (e.g., decide whether to even open the chunk pipeline)
  - Test the classification logic without depending on Tika's behavior
  - Run probe-only modes (vNext) for cheap "is this a scan?" detection
- **No OCR.** The probe is explicitly PDFBox-only. The whole point is to
  decide whether OCR is needed; running OCR here would defeat the purpose.
- **Char count, not OCR confidence.** Tesseract's hOCR output exposes per-word
  confidence which would be a richer signal — but it requires actually
  running OCR. Char count from PDFBox is the cheap proxy that tells us
  "does this page have a text layer at all." Per-word confidence is a vNext
  enhancement when we have an OCR'd pipeline path.
- **Non-PDF defaults to `textQuality=2`.** DOCX / HTML / Markdown / text all
  have inherent text content once Tika extracts them — no reason to flag
  them as low quality. Special-case at the entry point keeps the rest of the
  pipeline uniform.
- **Tunable thresholds.** "50 chars" and "500 chars" are reasonable for
  English prose pages but wrong for log-style files or sparse forms. Env
  knobs let an operator calibrate per corpus.

## Tests

`TextLayerProbeTest` (8 tests). Fixtures generate PDFs with controlled
text content via PDFBox itself.

- Full text layer (~720 chars/page) → `textQuality=2`.
- Sparse text (~184 chars) → `textQuality=1`.
- Minimal text (1 char) → `textQuality=0`.
- Multi-page PDF with mixed quality → per-page classification respected.
- Configurable thresholds: same PDF classified differently with looser /
  stricter floor.
- Non-PDF inputs → single `PageQuality(1, ..., 2)`.
- Invalid thresholds (`low > full`) rejected at probe time.
- `classify` boundary values exercised: 0, 49, 50, 499, 500.

The boundary tests are important — the `<` vs `<=` semantics on thresholds
matters for reproducibility, and these tests lock the behavior down.
