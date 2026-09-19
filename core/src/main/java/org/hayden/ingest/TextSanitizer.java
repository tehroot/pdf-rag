package org.hayden.ingest;

/**
 * Text cleanup applied once, where PDFBox output enters the pipeline.
 *
 * PDFBox emits unpaired UTF-16 surrogates from broken font encodings. Two
 * downstream JSON parsers reject them: llama-server (HTTP 500, "surrogate
 * U+D800..U+DBFF must be followed by U+DC00..U+DFFF") and Qdrant (HTTP 400,
 * "unexpected end of hex escape") — 30 and 9 DTIC files respectively,
 * 2026-09-18/19. Cleaning at extraction covers the embedding request and
 * the stored chunk payload alike.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /** Replace each unpaired surrogate with U+FFFD; same instance when clean. */
    public static String stripUnpairedSurrogates(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = null;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (sb != null) {
                    sb.append(c).append(s.charAt(i + 1));
                }
                i++;
            } else if (Character.isSurrogate(c)) {
                if (sb == null) {
                    sb = new StringBuilder(n).append(s, 0, i);
                }
                sb.append('\uFFFD');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
