package org.hayden.sizing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Standalone corpus-sizing scan: walks a directory of PDFs, deep-samples a
 * subset with the SAME machinery the ingest pipeline uses (PDFBox page-level
 * text extraction, the text_quality thresholds, the sliding-chunker math,
 * real page rendering at the production DPI), and extrapolates page counts,
 * chunk counts, embed times, and storage for both pipelines. Built to answer
 * "what does ingesting this corpus actually cost?" BEFORE committing a
 * terabyte-scale dataset to the pipeline — see docs/plans/ for the scale
 * workstream this feeds.
 *
 * <p>Not a CDI bean — plain {@code main()}, run via {@code scripts/size-corpus.sh}
 * (host) or {@code java -cp "/app/app/*:/app/lib/main/*"} inside the
 * pdf-rag-http container, where the corpus mounts are.
 */
public final class CorpusSizer {

    // Mirror application.properties defaults; env vars override like prod.
    private static final int DEFAULT_CHUNK_SIZE = envInt("INGEST_CHUNK_SIZE_CHARS", 1500);
    private static final int DEFAULT_CHUNK_OVERLAP = envInt("INGEST_CHUNK_OVERLAP_CHARS", 200);
    private static final int DEFAULT_DPI = envInt("COLPALI_RENDER_DPI", 150);
    private static final int THRESHOLD_LOW = envInt("INGEST_TEXT_QUALITY_THRESHOLD_LOW", 50);
    private static final int THRESHOLD_FULL = envInt("INGEST_TEXT_QUALITY_THRESHOLD_FULL", 500);

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a == null) {
            System.err.println(USAGE);
            System.exit(2);
        }

        // Pass 1: cheap full walk — file count + bytes only.
        List<Path> pdfs = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> walk = Files.walk(a.directory)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p) || underDotDir(a.directory, p)) continue;
                if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
                pdfs.add(p);
                totalBytes += Files.size(p);
            }
        }
        if (pdfs.isEmpty()) {
            System.err.println("No PDFs found under " + a.directory);
            System.exit(1);
        }

        // Pass 2: deep-analyze a reproducible random sample.
        List<Path> sample = new ArrayList<>(pdfs);
        Collections.shuffle(sample, new Random(a.seed));
        int sampleSize = a.sample <= 0 ? sample.size() : Math.min(a.sample, sample.size());
        sample = sample.subList(0, sampleSize);

        long sampledBytes = 0;
        long sampledPages = 0;
        long sampledChunks = 0;
        long sampledChunkChars = 0;
        long[] qualityPages = new long[3];
        int scannedDocs = 0;
        int errors = 0;
        int done = 0;
        List<DocStats> statsList = new ArrayList<>(sample.size());
        for (Path p : sample) {
            try {
                DocStats s = analyze(p.toFile(), a.chunkSize, a.chunkOverlap);
                statsList.add(s);
                sampledBytes += s.bytes;
                sampledPages += s.pages;
                sampledChunks += s.chunks;
                sampledChunkChars += s.chars;
                for (int q = 0; q < 3; q++) qualityPages[q] += s.pagesByQuality[q];
                if (s.pages > 0 && (double) s.pagesByQuality[0] / s.pages >= a.scanThreshold) {
                    scannedDocs++;
                }
            } catch (Exception e) {
                errors++;
                System.err.printf("  [error] %s: %s%n", p.getFileName(), brief(e));
            }
            if (++done % 25 == 0) {
                System.err.printf("  ...%d/%d sampled%n", done, sample.size());
            }
        }
        if (sampledBytes == 0) {
            System.err.println("Every sampled PDF failed to parse; cannot extrapolate.");
            System.exit(1);
        }

        // Pass 3: render a few real pages at prod DPI to measure PNG size.
        long avgPngBytes = a.renderPages > 0
                ? measurePngBytes(statsList, a.renderPages, a.dpi)
                : 150_000;

        // Extrapolate by bytes (pages-per-byte from the sample × corpus bytes).
        double factor = (double) totalBytes / sampledBytes;
        long projPages = Math.round(sampledPages * factor);
        long projChunks = Math.round(sampledChunks * factor);
        double q0Share = (double) qualityPages[0] / Math.max(1, sampledPages);
        double q1Share = (double) qualityPages[1] / Math.max(1, sampledPages);
        double scannedDocShare = (double) scannedDocs / Math.max(1, statsList.size());
        // Selective-visual page budget: pages belonging to "scanned" docs.
        long scannedDocPages = statsList.stream()
                .filter(s -> s.pages > 0 && (double) s.pagesByQuality[0] / s.pages >= a.scanThreshold)
                .mapToLong(s -> s.pages).sum();
        long projSelectivePages = Math.round(scannedDocPages * factor);
        long avgChunkChars = sampledChunks == 0 ? 0 : sampledChunkChars / sampledChunks;

        // Storage models (bytes).
        long textVectorBytes = projChunks * a.dims * 4L;
        long textPayloadBytes = projChunks * (avgChunkChars + 200L);
        long visualPerPage = Math.round(a.visualTokens * a.visualDim * 4L * 1.1);  // +10% pooled
        long visualAllBytes = projPages * visualPerPage;
        long visualSelBytes = projSelectivePages * visualPerPage;
        long imagesAllBytes = projPages * avgPngBytes;
        long imagesSelBytes = projSelectivePages * avgPngBytes;

        StringBuilder r = new StringBuilder();
        r.append("=== CORPUS ===============================================\n");
        r.append(String.format("  directory          %s%n", a.directory));
        r.append(String.format("  pdf files          %,d%n", pdfs.size()));
        r.append(String.format("  total size         %s%n", human(totalBytes)));
        r.append(String.format("  parse errors       %d of %d sampled (%.1f%%)%n",
                errors, sampleSize, 100.0 * errors / sampleSize));
        r.append("=== SAMPLE (basis for projections) =======================\n");
        r.append(String.format("  sampled            %,d files / %s (seed %d)%n",
                statsList.size(), human(sampledBytes), a.seed));
        r.append(String.format("  pages              %,d (avg %.1f/doc)%n",
                sampledPages, (double) sampledPages / Math.max(1, statsList.size())));
        r.append(String.format("  text quality       q0 (no text layer): %.1f%%   q1 (partial): %.1f%%   q2 (full): %.1f%%%n",
                100 * q0Share, 100 * q1Share, 100 * (1 - q0Share - q1Share)));
        r.append(String.format("  \"scanned\" docs     %.1f%% (>= %.0f%% q0 pages; drives the selective-visual numbers)%n",
                100 * scannedDocShare, 100 * a.scanThreshold));
        r.append("=== TEXT PIPELINE (projected, whole corpus) ==============\n");
        r.append(String.format("  pages              %,d%n", projPages));
        r.append(String.format("  chunks             %,d (chunk_size=%d overlap=%d, avg %d chars)%n",
                projChunks, a.chunkSize, a.chunkOverlap, avgChunkChars));
        r.append(String.format("  embed time         %s @ %.0f chunks/s%n",
                duration(projChunks / a.textRate), a.textRate));
        r.append(String.format("  qdrant storage     vectors %s (%d-dim fp32) + payload %s%n",
                human(textVectorBytes), a.dims, human(textPayloadBytes)));
        r.append("=== VISUAL PIPELINE (projected) ==========================\n");
        r.append(String.format("  ALL pages          %,d pages: %s @ %.1f pages/s | vectors %s | images %s%n",
                projPages, duration(projPages / a.visualRate), a.visualRate,
                human(visualAllBytes), human(imagesAllBytes)));
        r.append(String.format("  SELECTIVE (scanned docs only)%n"));
        r.append(String.format("                     %,d pages: %s | vectors %s | images %s%n",
                projSelectivePages, duration(projSelectivePages / a.visualRate),
                human(visualSelBytes), human(imagesSelBytes)));
        r.append(String.format("  page png           avg %s at %d dpi (%s)%n",
                human(avgPngBytes), a.dpi,
                a.renderPages > 0 ? "measured from " + a.renderPages + " rendered pages" : "assumed"));
        r.append("===========================================================\n");
        System.out.print(r);

        if (a.jsonOut != null) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("directory", a.directory.toString());
            j.put("files", pdfs.size());
            j.put("total_bytes", totalBytes);
            j.put("sampled_files", statsList.size());
            j.put("sampled_bytes", sampledBytes);
            j.put("sample_seed", a.seed);
            j.put("parse_errors", errors);
            j.put("projected_pages", projPages);
            j.put("projected_chunks", projChunks);
            j.put("avg_chunk_chars", avgChunkChars);
            j.put("q0_page_share", q0Share);
            j.put("q1_page_share", q1Share);
            j.put("scanned_doc_share", scannedDocShare);
            j.put("scan_threshold", a.scanThreshold);
            j.put("projected_selective_pages", projSelectivePages);
            j.put("text_embed_seconds", Math.round(projChunks / a.textRate));
            j.put("visual_all_seconds", Math.round(projPages / a.visualRate));
            j.put("visual_selective_seconds", Math.round(projSelectivePages / a.visualRate));
            j.put("text_vector_bytes", textVectorBytes);
            j.put("text_payload_bytes", textPayloadBytes);
            j.put("visual_all_bytes", visualAllBytes);
            j.put("visual_selective_bytes", visualSelBytes);
            j.put("images_all_bytes", imagesAllBytes);
            j.put("images_selective_bytes", imagesSelBytes);
            j.put("avg_png_bytes", avgPngBytes);
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(new File(a.jsonOut), j);
            System.err.println("json written: " + a.jsonOut);
        }
    }

    /** Per-document deep stats from one PDF, using production semantics. */
    public static DocStats analyze(File f, int chunkSize, int chunkOverlap) throws IOException {
        DocStats s = new DocStats();
        s.source = f;
        s.bytes = f.length();
        try (PDDocument doc = Loader.loadPDF(f)) {
            s.pages = doc.getNumberOfPages();
            s.pagesByQuality = new long[3];
            for (int i = 1; i <= s.pages; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                int chars = text == null ? 0 : text.length();
                s.chars += chars;
                s.pagesByQuality[classify(chars)]++;
                s.chunks += estimatePageChunks(chars, chunkSize, chunkOverlap);
            }
        }
        return s;
    }

    /** Same buckets as TextLayerProbe.classify with the prod thresholds. */
    public static int classify(int charCount) {
        if (charCount < THRESHOLD_LOW) return 0;
        if (charCount < THRESHOLD_FULL) return 1;
        return 2;
    }

    /**
     * Chunk count for one page under the sliding per-page chunker: pages are
     * chunked independently; a non-empty page always yields at least one chunk,
     * further chunks advance by (size - overlap).
     */
    public static long estimatePageChunks(int chars, int size, int overlap) {
        if (chars <= 0) return 0;
        if (chars <= size) return 1;
        int stride = Math.max(1, size - overlap);
        return 1 + (long) Math.ceil((double) (chars - size) / stride);
    }

    /** Render the first page of up to {@code count} sampled docs; average PNG bytes. */
    private static long measurePngBytes(List<DocStats> stats, int count, int dpi) {
        long total = 0;
        int rendered = 0;
        for (DocStats s : stats) {
            if (rendered >= count) break;
            if (s.pages == 0 || s.source == null) continue;
            try (PDDocument doc = Loader.loadPDF(s.source)) {
                PDFRenderer renderer = new PDFRenderer(doc);
                BufferedImage img = renderer.renderImageWithDPI(0, dpi, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                total += baos.size();
                rendered++;
            } catch (Exception e) {
                System.err.printf("  [render-skip] %s: %s%n", s.source.getName(), brief(e));
            }
        }
        return rendered == 0 ? 150_000 : total / rendered;
    }

    public static final class DocStats {
        public File source;
        public long bytes;
        public int pages;
        public long chars;
        public long chunks;
        public long[] pagesByQuality = new long[3];
    }

    // ---- plumbing -----------------------------------------------------------

    private static boolean underDotDir(Path root, Path p) {
        Path rel = root.relativize(p);
        for (Path seg : rel) {
            if (seg.toString().startsWith(".")) return true;
        }
        return false;
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": "
                + (m.length() > 120 ? m.substring(0, 120) + "…" : m));
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        for (String unit : new String[]{"KB", "MB", "GB", "TB", "PB"}) {
            v /= 1024;
            if (v < 1024) return String.format(Locale.ROOT, "%.1f %s", v, unit);
        }
        return String.format(Locale.ROOT, "%.1f EB", v / 1024);
    }

    public static String duration(double seconds) {
        if (seconds < 90) return String.format(Locale.ROOT, "%.0f s", seconds);
        double minutes = seconds / 60;
        if (minutes < 90) return String.format(Locale.ROOT, "%.0f min", minutes);
        double hours = minutes / 60;
        if (hours < 48) return String.format(Locale.ROOT, "%.1f h", hours);
        return String.format(Locale.ROOT, "%.1f days", hours / 24);
    }

    private static final String USAGE = """
            Usage: CorpusSizer <directory> [options]
              --sample N        deep-analyze N random PDFs (default 200; 0 = every file)
              --seed N          sampling seed for reproducible runs (default 42)
              --chunk-size N    chars per chunk (default env INGEST_CHUNK_SIZE_CHARS or 1500)
              --chunk-overlap N overlap chars (default env INGEST_CHUNK_OVERLAP_CHARS or 200)
              --text-rate N     projected text embed rate, chunks/sec (default 100)
              --visual-rate N   projected VLM rate, pages/sec (default 3)
              --dims N          text embedding dims (default 384 = bge-small)
              --visual-tokens N avg multivector tokens per page (default 768 = ColQwen2)
              --visual-dim N    per-token vector dim (default 128)
              --scan-threshold F  q0-page fraction that marks a doc "scanned" (default 0.5)
              --render N        render N sample pages to measure real PNG size (default 12; 0 = skip)
              --dpi N           render DPI (default env COLPALI_RENDER_DPI or 150)
              --json PATH       also write a machine-readable JSON report
            """;

    record Args(Path directory, int sample, long seed, int chunkSize, int chunkOverlap,
                double textRate, double visualRate, int dims, int visualTokens, int visualDim,
                double scanThreshold, int renderPages, int dpi, String jsonOut) {

        static Args parse(String[] argv) {
            if (argv.length == 0) return null;
            Path dir = Path.of(argv[0]);
            if (!Files.isDirectory(dir)) {
                System.err.println("Not a directory: " + dir);
                return null;
            }
            int sample = 200, chunkSize = DEFAULT_CHUNK_SIZE, chunkOverlap = DEFAULT_CHUNK_OVERLAP;
            int dims = 384, visualTokens = 768, visualDim = 128, render = 12, dpi = DEFAULT_DPI;
            long seed = 42;
            double textRate = 100, visualRate = 3, scanThreshold = 0.5;
            String json = null;
            for (int i = 1; i < argv.length; i++) {
                String k = argv[i];
                String v = i + 1 < argv.length ? argv[i + 1] : null;
                switch (k) {
                    case "--sample" -> sample = Integer.parseInt(req(k, v));
                    case "--seed" -> seed = Long.parseLong(req(k, v));
                    case "--chunk-size" -> chunkSize = Integer.parseInt(req(k, v));
                    case "--chunk-overlap" -> chunkOverlap = Integer.parseInt(req(k, v));
                    case "--text-rate" -> textRate = Double.parseDouble(req(k, v));
                    case "--visual-rate" -> visualRate = Double.parseDouble(req(k, v));
                    case "--dims" -> dims = Integer.parseInt(req(k, v));
                    case "--visual-tokens" -> visualTokens = Integer.parseInt(req(k, v));
                    case "--visual-dim" -> visualDim = Integer.parseInt(req(k, v));
                    case "--scan-threshold" -> scanThreshold = Double.parseDouble(req(k, v));
                    case "--render" -> render = Integer.parseInt(req(k, v));
                    case "--dpi" -> dpi = Integer.parseInt(req(k, v));
                    case "--json" -> json = req(k, v);
                    default -> {
                        System.err.println("Unknown option: " + k);
                        return null;
                    }
                }
                i++;
            }
            return new Args(dir, sample, seed, chunkSize, chunkOverlap, textRate, visualRate,
                    dims, visualTokens, visualDim, scanThreshold, render, dpi, json);
        }

        private static String req(String key, String val) {
            if (val == null) throw new IllegalArgumentException(key + " requires a value");
            return val;
        }
    }

    private CorpusSizer() {
    }
}
