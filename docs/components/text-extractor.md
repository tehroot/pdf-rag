# TextExtractor (Tika + PDFBox per-page)

`core/src/main/java/org/hayden/backend/qdrant/TextExtractor.java` (~120 lines).
The first step of the Qdrant pipeline after `FileFetcher` — turns binary
document bytes into plain text the chunker can split.

**Two extraction paths** depending on what downstream needs:
- `extract(file)` — single concatenated string via Tika `AutoDetectParser`.
  The original behavior; covers every format Tika knows.
- `extractPerPage(file)` — per-page text. For PDFs, uses **PDFBox directly**
  to get one chunk of text per page; for everything else, falls back to
  `extract(file)` and returns a single-element list at page 1.

Not used by the Open WebUI backend, which hands raw bytes to Open WebUI's own
extraction pipeline.

## What it does

The per-page extraction is what `ChunkPipeline` uses today — the chunker
needs per-page text to tag each chunk with its source page range. Tika's
`AutoDetectParser` doesn't expose page-level callbacks cleanly, so for PDFs
we sidestep Tika and call PDFBox directly.

```java
public String extract(FetchedFile file);                    // single blob
public List<PageText> extractPerPage(FetchedFile file);     // per-page (PDFs)
```

Both throw `IngestException` if no text was extractable — almost always a
bad input file or an unsupported format, and we'd rather fail fast than
upsert empty chunks.

## Interface

```java
@ConfigProperty(name = "ingest.extract.max-chars", defaultValue = "10000000")
int maxChars;

public String extract(FetchedFile file);
public List<PageText> extractPerPage(FetchedFile file);
```

`maxChars` becomes the buffer size of Tika's `BodyContentHandler` AND a hard
cap on per-page concatenated length in `extractPerPage`. Tika defaults to
100 000 chars (intentionally low — its CLI is meant for one-document
streaming), which silently truncates large PDFs and was a real bug in v1. We
bumped to 10 MB worth of characters, enough for a several-hundred-page document.

## `extractPerPage` — the path the chunker uses

```java
public List<PageText> extractPerPage(FetchedFile file) {
    if (isPdf(file)) {
        return extractPdfPerPage(file);     // PDFBox directly, per-page
    }
    // Non-PDF formats: treat as a single conceptual page.
    return List.of(new PageText(1, extract(file)));
}
```

The PDF path uses PDFBox's `PDFTextStripper` with `setStartPage(i)` /
`setEndPage(i)` to extract one page at a time. Each call yields the
page's text-layer content (no OCR — that's `TextLayerProbe`'s and
Tika's job separately). Empty pages are kept as `PageText(n, "")` so
the chunker's page-range mapping stays correct when chunks straddle empty
pages.

If a PDF has no text layer at all (scanned PDF with no OCR baked in), every
page returns empty and the method throws with a clear message:
"PDFBox extracted no text from X (likely a scanned PDF without a text layer;
ColPali / OCR is required to read it)".

`extract(file)` (the single-blob path) still uses Tika `AutoDetectParser`
for non-PDF formats. Tika handles OCR transparently via Tesseract for image
embeddings in DOCX/PPTX, but that's a property of Tika's parsers — not
something we control.

## Internals

```java
public String extract(FetchedFile file) {
    BodyContentHandler handler = new BodyContentHandler(maxChars);
    Metadata metadata = new Metadata();
    if (file.filename() != null) {
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.filename());
    }
    if (file.contentType() != null) {
        metadata.set(Metadata.CONTENT_TYPE, file.contentType());
    }

    try (ByteArrayInputStream in = new ByteArrayInputStream(file.content())) {
        new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
    } catch (IOException | SAXException | TikaException e) {
        throw new IngestException("Failed to extract text from " + file.filename(), e);
    }

    String text = handler.toString();
    if (text == null || text.isBlank()) {
        throw new IngestException("Tika extracted no text from " + file.filename()
                + " (content-type=" + file.contentType() + ")");
    }
    return text;
}
```

Step-by-step:

1. **Metadata hints.** Tika's auto-detection works even without hints, but
   feeding the filename and content-type makes it a) faster (skips magic-byte
   scanning), and b) more accurate when the content-type is more specific
   than what bytes alone reveal (e.g. distinguishing OOXML variants).
2. **`BodyContentHandler(maxChars)` instead of the no-arg constructor.** The
   no-arg version uses Tika's 100 000-char default — silent truncation, often
   without any obvious sign in the extracted text. The explicit cap is
   load-bearing.
3. **`AutoDetectParser`.** Tika's umbrella parser that delegates to format-
   specific parsers via SPI. With `tika-parsers-standard-package` on the
   classpath, that includes PDFBox (PDF), POI (DOCX/XLSX/PPTX), Jericho (HTML),
   commons-compress (archives), and many more.
4. **`new ParseContext()` (empty).** No nested-document handler, no OCR config
   tweaks. If you want OCR or recursive parsing later, this is the parameter
   that gets configured.
5. **Empty output is a failure.** Tika returning an empty string means either
   (a) the file format isn't supported (no parser claimed it), or (b) the file
   is corrupted / actually empty. Either way, propagating an empty string to
   the chunker would produce zero chunks and confuse downstream consumers — so
   we throw `IngestException` here with the filename + content-type, which is
   usually enough to diagnose.

## Failure modes

| Case | Result |
|------|--------|
| File format Tika can't parse | `IngestException` (Tika's parser may throw `TikaException`, or it succeeds with empty text → we throw the "no text" variant). |
| File over `maxChars` | Tika throws `SAXException("Your document contained more than … characters …")` after writing the cap. Wrapped in `IngestException`. Action: bump `ingest.extract.max-chars`. |
| File legitimately yields no extractable text (e.g. scanned PDF with no OCR layer) | `IngestException("Tika extracted no text from … (content-type=…)")`. To recover: install Tesseract on the host and let Tika's `TesseractOCRParser` run, or pre-OCR the file. |
| Tika dep missing for the format | Same as "no parser claimed it" — empty text. Make sure `tika-parsers-standard-package` is on the classpath; we exclude only the SLF4J bindings, never any parsers. |

## Why it's like this

- **`AutoDetectParser` instead of per-format parsers.** We could `new
  PDFParser()` for PDFs, etc., but Tika's strength is *not having to know* —
  the agent passes a URL, we pass bytes through, Tika finds the right parser.
  Adding DOCX/HTML/etc. costs nothing.
- **Hand the filename + content-type as metadata.** Tika's `Metadata.CONTENT_TYPE`
  short-circuits magic-byte detection when set; `TikaCoreProperties.RESOURCE_NAME_KEY`
  (the renamed-in-3.x version of `Metadata.RESOURCE_NAME_KEY`) does the same
  via filename extension. Both are advisory — Tika overrides if the bytes
  disagree.
- **No streaming.** `FileFetcher` already materialized the whole file in
  memory (100 MB cap). Streaming Tika is possible (`AutoDetectParser.parse`
  takes an `InputStream`) but the `BodyContentHandler` collects characters
  into a `StringBuilder`-backed buffer anyway, so the memory win is marginal.
  If huge files become common, the right move is a streaming chunker that
  emits chunks as Tika produces SAX events — much bigger refactor.
- **`tika-parsers-standard-package`, not `tika-parsers-full-package`.** The
  "full" package adds OCR (Tesseract), scientific (NetCDF), and a few other
  niche packages totaling another ~80 MB. We can add it later if we need OCR
  out of the box; for now Tesseract is opt-in via the host.
- **No OCR by default.** With `tika-parsers-standard-package`, Tika's
  `TesseractOCRParser` is on the classpath but disabled unless `tesseract` is
  installed on the host and reachable on `PATH`. To enable: install Tesseract,
  restart the server. No config flag in this app exposes OCR knobs yet.

## Tests

`TextExtractorTest` (3 tests):

- `plainText_extractsAsIs` — Tika round-trips text/plain content.
- `html_stripsTagsKeepsText` — `<h1>Title</h1>` → `Title` (Tika strips markup).
- `emptyInput_throws` — empty bytes raise `IngestException`.

PDF extraction is covered by the live smoke-test path in
[../deployment.md](../deployment.md) rather than a unit test — packaging a
minimal valid PDF as a fixture works but adds binary blobs to the repo. Tika's
own test suite covers the format parsers; we're only verifying our wiring of
metadata hints + `BodyContentHandler` + empty-output guard, which the two
text-based tests demonstrate.

If you ever need PDF coverage in CI, the cheapest path is to generate a tiny
PDF on the fly with PDFBox (`PDDocument` + a single page with one text
string), which avoids checking in a binary fixture.
