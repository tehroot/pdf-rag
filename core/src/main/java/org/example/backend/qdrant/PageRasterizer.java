package org.example.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders PDF pages to PNG byte arrays via PDFBox's {@link PDFRenderer}. Used
 * by {@link ColPaliPipeline} to produce the page images that the ColPali
 * sidecar embeds and that the {@code inspect_page} tool returns.
 *
 * <p>Two render parameters control quality and file size:
 * <ul>
 *   <li>{@code ingest.colpali.render-dpi} (default 150) — higher = better visual
 *       fidelity for ColPali, larger PNGs. 150 is a reasonable starting point
 *       for screen-readable documents; 200-300 for fine-detail technical drawings.
 *   <li>{@code ingest.colpali.render-image-type} (default {@code RGB}) — passed
 *       to PDFBox as {@link ImageType}. Use {@code RGB} for color docs and
 *       {@code GRAY} for storage savings on text-only scans.
 * </ul>
 *
 * <p>Non-PDF inputs throw {@link IngestException}. The visual pipeline is
 * intentionally PDF-only in v1; other formats stay on the text-only path.
 */
@ApplicationScoped
public class PageRasterizer {

    @ConfigProperty(name = "ingest.colpali.render-dpi", defaultValue = "150")
    int dpi;

    @ConfigProperty(name = "ingest.colpali.render-image-type", defaultValue = "RGB")
    String imageType;

    /**
     * Render every page of the input PDF to a PNG. Returns the rendered bytes
     * in page order (1-indexed in the returned {@link RenderedPage#pageNumber()}).
     */
    public List<RenderedPage> renderAll(FetchedFile file) {
        if (!isPdf(file)) {
            throw new IngestException("PageRasterizer only supports PDFs; got "
                    + file.contentType() + " for " + file.filename());
        }
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                throw new IngestException("PDF " + file.filename() + " has 0 pages");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            ImageType type = parseImageType(imageType);
            List<RenderedPage> out = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, type);
                byte[] png = encodePng(image, file.filename(), i + 1);
                out.add(new RenderedPage(i + 1, image.getWidth(), image.getHeight(), png));
            }
            return out;
        } catch (IOException e) {
            throw new IngestException("Failed to rasterize PDF " + file.filename(), e);
        }
    }

    /**
     * Render a single page (1-indexed) of the input PDF to a PNG. Used by the
     * {@code inspect_page} tool to re-render on demand if the stored image is
     * missing, and as a convenience for tests.
     */
    public RenderedPage renderPage(FetchedFile file, int pageNumber) {
        if (!isPdf(file)) {
            throw new IngestException("PageRasterizer only supports PDFs; got "
                    + file.contentType() + " for " + file.filename());
        }
        if (pageNumber < 1) {
            throw new IngestException("page_number must be >= 1, got " + pageNumber);
        }
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            int pageCount = doc.getNumberOfPages();
            if (pageNumber > pageCount) {
                throw new IngestException("PDF " + file.filename() + " has " + pageCount
                        + " pages; requested " + pageNumber);
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            ImageType type = parseImageType(imageType);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, dpi, type);
            byte[] png = encodePng(image, file.filename(), pageNumber);
            return new RenderedPage(pageNumber, image.getWidth(), image.getHeight(), png);
        } catch (IOException e) {
            throw new IngestException("Failed to rasterize page " + pageNumber
                    + " of PDF " + file.filename(), e);
        }
    }

    private static byte[] encodePng(BufferedImage image, String filename, int pageNumber) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            boolean ok = ImageIO.write(image, "png", baos);
            if (!ok) {
                throw new IngestException("No PNG writer available for page " + pageNumber
                        + " of " + filename);
            }
        } catch (IOException e) {
            throw new IngestException("Failed to encode PNG for page " + pageNumber
                    + " of " + filename, e);
        }
        return baos.toByteArray();
    }

    private static ImageType parseImageType(String raw) {
        try {
            return ImageType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IngestException("Unknown ingest.colpali.render-image-type='" + raw
                    + "' (expected RGB | ARGB | BGR | BINARY | GRAY)");
        }
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }

    /** A rendered page: PNG bytes plus dimensions and page number. */
    public record RenderedPage(int pageNumber, int width, int height, byte[] pngBytes) {
    }
}
