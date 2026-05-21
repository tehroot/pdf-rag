# PageRasterizer

`core/.../backend/qdrant/PageRasterizer.java` (~130 lines). Renders PDF pages
to PNG byte arrays via Apache PDFBox's `PDFRenderer`. Used by
`ColPaliPipeline` at ingest to produce the page images the sidecar embeds,
and by `inspect_page` to re-render on demand if the stored image is missing.

## What it does

Two methods:

```java
public List<RenderedPage> renderAll(FetchedFile pdf);
public RenderedPage renderPage(FetchedFile pdf, int pageNumber);

public record RenderedPage(int pageNumber, int width, int height, byte[] pngBytes);
```

Both refuse non-PDF inputs with a clear error. v1 is PDF-only on the visual
side; other formats stay on the text-only path.

## Configuration

| Key | Env | Default |
|-----|-----|---------|
| `ingest.colpali.render-dpi` | `COLPALI_RENDER_DPI` | `150` |
| `ingest.colpali.render-image-type` | `COLPALI_RENDER_IMAGE_TYPE` | `RGB` |

DPI controls quality vs file size. 150 DPI is fine for screen-readable
documents; bump to 200-300 for fine-detail technical drawings. RGB / GRAY /
BINARY / ARGB / BGR all map to PDFBox's `ImageType` enum.

## Internals

```java
public List<RenderedPage> renderAll(FetchedFile file) {
    if (!isPdf(file)) throw new IngestException("only supports PDFs");
    try (PDDocument doc = Loader.loadPDF(file.content())) {
        PDFRenderer renderer = new PDFRenderer(doc);
        ImageType type = parseImageType(imageType);
        List<RenderedPage> out = new ArrayList<>(doc.getNumberOfPages());
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, dpi, type);
            byte[] png = encodePng(image, file.filename(), i + 1);
            out.add(new RenderedPage(i + 1, image.getWidth(), image.getHeight(), png));
        }
        return out;
    }
}
```

`PDFBox 3` API specifics:
- `Loader.loadPDF(byte[])` replaces the old `PDDocument.load(byte[])`.
- `PDFRenderer.renderImageWithDPI(zeroIndexedPage, dpi, ImageType)` produces a
  `BufferedImage`.
- `ImageIO.write(image, "png", baos)` encodes to PNG bytes.

`isPdf(file)` checks both `content_type` (looks for "pdf") and the filename
suffix (`.pdf`). Either is sufficient — they typically agree.

## Failure modes

| Case | Throws |
|------|--------|
| Non-PDF input | `IngestException("PageRasterizer only supports PDFs; got ...")` |
| PDF has 0 pages | `IngestException("PDF X has 0 pages")` |
| `pageNumber < 1` | `IngestException("page_number must be >= 1")` |
| `pageNumber > pageCount` | `IngestException("PDF X has N pages; requested M")` |
| Unknown image-type string | `IngestException("Unknown ... image-type=...")` |
| PDFBox I/O failure | `IngestException("Failed to rasterize PDF ...", IOException)` |

PNG encoding itself never fails for valid `BufferedImage` inputs (Java's
`ImageIO` ships a PNG writer in every standard JVM).

## Why it's like this

- **One method per use case.** `renderAll` for ingest (the common path) and
  `renderPage` for `inspect_page`'s re-render fallback. Both share the same
  setup but `renderPage` doesn't pay for loading every page into memory.
- **Returns the dimensions explicitly.** Could be re-read from the PNG bytes,
  but exposing them via `RenderedPage` saves callers a decode step.
- **PDF-only.** Tika handles many formats for text extraction, but
  rasterizing a DOCX requires converting to PDF first (LibreOffice / unoconv
  or similar). Out of scope for v1. If the corpus has lots of DOCX, run a
  format-conversion step upstream before ingest.
- **PDFBox 3 over alternatives.** PDFBox is already on the classpath via
  Tika; reusing it keeps the dep tree small. ICEpdf / iText would also work
  but add license complexity (iText is AGPL/commercial).

## Tests

`PageRasterizerTest` (9 tests). The fixtures generate small PDFs on the fly
via PDFBox itself — no binary fixtures checked in.

- Single-page render produces valid PNG bytes.
- Multi-page render returns pages in order.
- DPI tuning changes pixel dimensions monotonically.
- GRAY image-type works (smoke for non-RGB).
- `renderPage` with a specific page number returns that page.
- Out-of-range page number throws with a clear message.
- Non-PDF input throws.
- Invalid image-type string throws.

PNG validation uses the magic-number check (89 50 4E 47 0D 0A 1A 0A) — fast,
no dependency on parsing the file.
