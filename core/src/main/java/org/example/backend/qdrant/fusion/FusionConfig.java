package org.example.backend.qdrant.fusion;

/**
 * Operator-tunable fusion parameters. Built lazily from {@code @ConfigProperty}
 * values by {@code FusionEngine} so the per-strategy code can stay
 * unit-testable with explicit values.
 *
 * @param topK              how many final hits to return after fusion
 * @param rrfK              the RRF constant ({@code 60} is the standard default)
 * @param textWeight        weighted-fusion: text-pipeline contribution (0..1)
 * @param visualWeight      weighted-fusion: visual-pipeline contribution (0..1)
 * @param textScoreFloor    weighted-fusion: empirical max text cosine score
 *                          for normalization to [0, 1]
 * @param visualScoreFloor  weighted-fusion: empirical max ColPali MAX_SIM
 *                          score for normalization to [0, 1]
 */
public record FusionConfig(
        int topK,
        int rrfK,
        double textWeight,
        double visualWeight,
        double textScoreFloor,
        double visualScoreFloor) {

    /** Sensible defaults that match the plan. Tests use this as a starting point. */
    public static FusionConfig defaults() {
        return new FusionConfig(
                5,       // topK
                60,      // rrfK
                0.5,     // textWeight
                0.5,     // visualWeight
                1.0,     // textScoreFloor (cosine already in [0,1])
                50.0     // visualScoreFloor (empirical for ColPali max_sim)
        );
    }

    /** A copy with a different topK; lets the engine override per-call. */
    public FusionConfig withTopK(int newTopK) {
        return new FusionConfig(newTopK, rrfK, textWeight, visualWeight,
                textScoreFloor, visualScoreFloor);
    }
}
