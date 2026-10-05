/* MaxSim prefetch over int8 pooled page vectors.
 * Host build (gcc): the offload pragmas are ignored, the same loops run on the CPU.
 * Phi build (icc, MPSS): -qoffload, the loops run on the co-processor with the
 * matrix resident there across queries.
 *
 * Data: q8[N][32][320] int8 (per-page symmetric scale sc[N]), query Qi[QR][320] int8 with per-row scale qsc[QR].
 * score[p] = sc[p] * sum_r qsc[r] * max_j dot(Qi[r], q8[p][j])   (per-page scale factors out of max and sum)
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <time.h>
#ifdef __INTEL_OFFLOAD
#define PHI __attribute__((target(mic)))
#else
#define PHI
#endif
#define ROWS 32
#define DIM 320
#define MAXQ 32

PHI static void maxsim_block(const int8_t *q8, const float *sc, long n,
                             const int8_t *Qi, const float *qsc, int qr, float *out) {
    #pragma omp parallel for schedule(static)
    for (long p = 0; p < n; p++) {
        const int8_t *page = q8 + p * ROWS * DIM;
        float acc = 0.f;
        for (int r = 0; r < qr; r++) {
            const int8_t *q = Qi + r * DIM;
            int best = -2147483647;
            for (int j = 0; j < ROWS; j++) {
                const int8_t *row = page + j * DIM;
                int d = 0;
                #pragma omp simd reduction(+:d)
                for (int k = 0; k < DIM; k++) d += (int)q[k] * (int)row[k];
                if (d > best) best = d;
            }
            acc += qsc[r] * (float)best;
        }
        out[p] = sc[p] * acc;
    }
}

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
static int cmp_desc(const void *a, const void *b) { const float *x = *(const float **)a, *y = *(const float **)b; return (*x < *y) - (*x > *y); }

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s pooled_rows.i8 scales.f32 queries.bin [k]\n", argv[0]); return 2; }
    int k = argc > 4 ? atoi(argv[4]) : 200;
    FILE *f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long bytes = ftell(f); fseek(f, 0, SEEK_SET);
    long n = bytes / (ROWS * DIM);
    int8_t *q8 = malloc(bytes); if (fread(q8, 1, bytes, f) != (size_t)bytes) return 3; fclose(f);
    float *sc = malloc(n * sizeof(float)); f = fopen(argv[2], "rb"); if (fread(sc, 4, n, f) != (size_t)n) return 3; fclose(f);
    /* queries.bin: int32 nq, then per query: int32 qr, float qsc[qr], int8 Qi[qr][DIM] */
    f = fopen(argv[3], "rb"); int nq; if (fread(&nq, 4, 1, f) != 1) return 3;
    float *out = malloc(n * sizeof(float)); const float **ptrs = malloc(n * sizeof(float *));
    fprintf(stderr, "pages %ld, queries %d, matrix %.2f GB\n", n, nq, bytes / 1e9);
#ifdef __INTEL_OFFLOAD
    #pragma offload_transfer target(mic:0) in(q8:length(bytes) alloc_if(1) free_if(0)) in(sc:length(n) alloc_if(1) free_if(0))
#endif
    double total = 0;
    for (int qi = 0; qi < nq; qi++) {
        int qr; if (fread(&qr, 4, 1, f) != 1) return 3;
        float qsc[MAXQ]; int8_t Qi[MAXQ * DIM];
        if (fread(qsc, 4, qr, f) != (size_t)qr || fread(Qi, 1, qr * DIM, f) != (size_t)(qr * DIM)) return 3;
        double t0 = now();
#ifdef __INTEL_OFFLOAD
        #pragma offload target(mic:0) in(Qi:length(qr*DIM)) in(qsc:length(qr)) out(out:length(n)) nocopy(q8:length(bytes) alloc_if(0) free_if(0)) nocopy(sc:length(n) alloc_if(0) free_if(0))
#endif
        maxsim_block(q8, sc, n, Qi, qsc, qr, out);
        for (long p = 0; p < n; p++) ptrs[p] = &out[p];
        /* partial selection: full sort is fine for a benchmark harness */
        qsort(ptrs, n, sizeof(float *), cmp_desc);
        double dt = now() - t0; total += dt;
        printf("query %d %.1f ms:", qi, dt * 1e3);
        for (int i = 0; i < k; i++) printf(" %ld", (long)(ptrs[i] - out));
        printf("\n");
    }
    fprintf(stderr, "mean %.1f ms per query\n", total / nq * 1e3);
    return 0;
}
