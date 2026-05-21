package org.hayden.backend.qdrant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic UUID v5 generation used for Qdrant point IDs.
 *
 * <p>UUID v5 = SHA-1(namespace ++ name), with the version + variant bits set
 * per RFC 4122. Same input → same UUID, every time.
 *
 * <p>The namespace is project-specific (RFC 4122 namespace_DNS UUID) and
 * shared across chunks and pages so the two collections' ID spaces don't
 * collide.
 */
public final class UuidV5 {

    private static final UUID NAMESPACE =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private UuidV5() {
    }

    /** UUID v5 for the chunk at {@code chunkIndex} within document {@code docId}. */
    public static String forChunk(String docId, int chunkIndex) {
        return compute(NAMESPACE, docId + ":" + chunkIndex).toString();
    }

    /** UUID v5 for {@code pageNumber} of document {@code docId}. */
    public static String forPage(String docId, int pageNumber) {
        return compute(NAMESPACE, docId + ":page:" + pageNumber).toString();
    }

    static UUID compute(UUID namespace, String name) {
        byte[] nsBytes = uuidToBytes(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] concat = new byte[nsBytes.length + nameBytes.length];
        System.arraycopy(nsBytes, 0, concat, 0, nsBytes.length);
        System.arraycopy(nameBytes, 0, concat, nsBytes.length, nameBytes.length);
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-1").digest(concat);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // RFC 4122 variant
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
        return new UUID(msb, lsb);
    }

    private static byte[] uuidToBytes(UUID u) {
        byte[] out = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) out[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 8; i < 16; i++) out[i] = (byte) (lsb >>> (8 * (15 - i)));
        return out;
    }
}
