/* phi_bench: measure the three numbers the MaxSim performance model rests on,
 * on a Xeon Phi 7120P (Knights Corner), built with the MPSS k1om GNU toolchain:
 *
 *   fma         peak vpmadd231d rate, registers only (24 independent chains)
 *   kernl1      the MaxSim inner step, data in L1: one {sint8} load + 20
 *               vpmadd231d with a {1to16} broadcast query operand
 *   stream      memory read bandwidth, 64-byte vmovdqa32 loads
 *   stream8     memory read bandwidth through {sint8} upconverting loads (16 B each)
 *   kernstream  the MaxSim inner step streaming int8 page data from GDDR5
 *
 * Threads: -p threads per core (1..4) on cores 0..59, pinned to logical CPUs
 * 4c+1 .. 4c+p; core 60 (CPUs 0, 241-243) is left to uOS.
 *
 * Build (in the phi-host VM):
 *   . /opt/mpss/3.8.6/environment-setup-k1om-mpss-linux
 *   k1om-mpss-linux-gcc $CFLAGS -O2 -Wa,-march=k1om phi_bench.c -o phi_bench -lpthread
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>

#define CORES 60
#define GHZ 1.238095
#define DIMS 320
#define QROWS 20

enum { T_FMA, T_KERNL1, T_STREAM, T_STREAM8, T_KERNSTREAM };
static const char *names[] = { "fma", "kernl1", "stream", "stream8", "kernstream" };

static int test = -1, tpc = 4, nthreads, prefetch = 1;
static long iters = 20000000, reps = 10;
static size_t chunk = 32u << 20;           /* bytes per thread for the stream tests */
static pthread_barrier_t bar;
static int32_t *qglob;                     /* query, dim-major: [DIMS][QROWS] int32 */

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static void pin(int cpu) {
    cpu_set_t s;
    CPU_ZERO(&s);
    CPU_SET(cpu, &s);
    pthread_setaffinity_np(pthread_self(), sizeof s, &s);
}

/* 24 independent multiply-add chains on zmm0..zmm23 */
#define F(n) "vpmadd231d %%zmm24, %%zmm25, %%zmm" #n "\n\t"
static void run_fma(long it) {
    __asm__ volatile("vpxord %%zmm24, %%zmm24, %%zmm24\n\tvpxord %%zmm25, %%zmm25, %%zmm25" ::: "memory");
    for (long i = 0; i < it; i++)
        __asm__ volatile(F(0) F(1) F(2) F(3) F(4) F(5) F(6) F(7) F(8) F(9) F(10) F(11)
                         F(12) F(13) F(14) F(15) F(16) F(17) F(18) F(19) F(20) F(21) F(22) F(23) ::: "memory");
}

/* One MaxSim step: 16 page rows of one dim (int8, widened on load) times the
 * 20 query values of that dim (int32, broadcast), into 20 accumulators. */
#define K(n, off) "vpmadd231d " #off "(%1)%{1to16%}, %%zmm20, %%zmm" #n "\n\t"
static inline void kern_step(const int8_t *pg, const int32_t *q) {
    __asm__ volatile("vmovdqa32 (%0)%{sint8%}, %%zmm20\n\t"
                     K(0, 0) K(1, 4) K(2, 8) K(3, 12) K(4, 16) K(5, 20) K(6, 24) K(7, 28) K(8, 32) K(9, 36)
                     K(10, 40) K(11, 44) K(12, 48) K(13, 52) K(14, 56) K(15, 60) K(16, 64) K(17, 68)
                     K(18, 72) K(19, 76)
                     :: "r"(pg), "r"(q) : "memory");
}

static inline void pf(const int8_t *p) {
    __asm__ volatile("vprefetch0 512(%0)\n\tvprefetch1 4096(%0)" :: "r"(p) : "memory");
}

static void *worker(void *arg) {
    long id = (long)arg;
    int core = id / tpc, slot = id % tpc;
    pin(4 * core + 1 + slot);

    int8_t *buf = NULL;
    size_t bytes = (test == T_KERNL1) ? DIMS * 16 : chunk;
    if (test != T_FMA) {
        if (posix_memalign((void **)&buf, 2u << 20, bytes)) { perror("posix_memalign"); exit(2); }
        madvise(buf, bytes, 14 /* MADV_HUGEPAGE */);
        memset(buf, (int)(id & 0x7f), bytes);        /* first touch */
    }
    pthread_barrier_wait(&bar);                      /* setup done  */
    pthread_barrier_wait(&bar);                      /* start       */

    switch (test) {
    case T_FMA:
        run_fma(iters);
        break;
    case T_KERNL1:
        for (long i = 0; i < iters / DIMS; i++)
            for (int k = 0; k < DIMS; k++) kern_step(buf + k * 16, qglob + k * QROWS);
        break;
    case T_STREAM:
        for (long r = 0; r < reps; r++)
            for (size_t off = 0; off < chunk; off += 256) {
                const int8_t *p = buf + off;
                if (prefetch) { pf(p); pf(p + 64); pf(p + 128); pf(p + 192); }
                __asm__ volatile("vmovdqa32 (%0), %%zmm0\n\tvmovdqa32 64(%0), %%zmm1\n\t"
                                 "vmovdqa32 128(%0), %%zmm2\n\tvmovdqa32 192(%0), %%zmm3\n\t"
                                 "vpaddd %%zmm0, %%zmm4, %%zmm4\n\tvpaddd %%zmm1, %%zmm5, %%zmm5\n\t"
                                 "vpaddd %%zmm2, %%zmm6, %%zmm6\n\tvpaddd %%zmm3, %%zmm7, %%zmm7"
                                 :: "r"(p) : "memory");
            }
        break;
    case T_STREAM8:
        for (long r = 0; r < reps; r++)
            for (size_t off = 0; off < chunk; off += 64) {
                const int8_t *p = buf + off;
                if (prefetch) pf(p);
                __asm__ volatile("vmovdqa32 (%0)%{sint8%}, %%zmm0\n\tvmovdqa32 16(%0)%{sint8%}, %%zmm1\n\t"
                                 "vmovdqa32 32(%0)%{sint8%}, %%zmm2\n\tvmovdqa32 48(%0)%{sint8%}, %%zmm3\n\t"
                                 "vpaddd %%zmm0, %%zmm4, %%zmm4\n\tvpaddd %%zmm1, %%zmm5, %%zmm5\n\t"
                                 "vpaddd %%zmm2, %%zmm6, %%zmm6\n\tvpaddd %%zmm3, %%zmm7, %%zmm7"
                                 :: "r"(p) : "memory");
            }
        break;
    case T_KERNSTREAM:
        for (long r = 0; r < reps; r++) {
            int k = 0;
            for (size_t off = 0; off < chunk; off += 16) {
                if (prefetch && (off & 63) == 0) pf(buf + off);
                kern_step(buf + off, qglob + k * QROWS);
                if (++k == DIMS) k = 0;
            }
        }
        break;
    }
    pthread_barrier_wait(&bar);                      /* end */
    return NULL;
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-t") && i + 1 < argc) {
            for (int j = 0; j < 5; j++) if (!strcmp(argv[i + 1], names[j])) test = j;
            i++;
        } else if (!strcmp(argv[i], "-p") && i + 1 < argc) tpc = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-n") && i + 1 < argc) iters = atol(argv[++i]);
        else if (!strcmp(argv[i], "-r") && i + 1 < argc) reps = atol(argv[++i]);
        else if (!strcmp(argv[i], "-m") && i + 1 < argc) chunk = (size_t)atol(argv[++i]) << 20;
        else if (!strcmp(argv[i], "-nopf")) prefetch = 0;
    }
    if (test < 0 || tpc < 1 || tpc > 4) {
        fprintf(stderr, "usage: phi_bench -t fma|kernl1|stream|stream8|kernstream [-p 1..4] [-n iters] [-r reps] [-m MB/thread] [-nopf]\n");
        return 1;
    }
    nthreads = CORES * tpc;
    if (posix_memalign((void **)&qglob, 64, DIMS * QROWS * sizeof(int32_t))) return 2;
    for (int i = 0; i < DIMS * QROWS; i++) qglob[i] = (i * 37) % 255 - 127;

    pthread_barrier_init(&bar, NULL, nthreads + 1);
    pthread_t *th = calloc(nthreads, sizeof *th);
    for (long i = 0; i < nthreads; i++) pthread_create(&th[i], NULL, worker, (void *)i);
    pthread_barrier_wait(&bar);
    pthread_barrier_wait(&bar);
    double t0 = now();
    pthread_barrier_wait(&bar);
    double t1 = now(), dt = t1 - t0;
    for (int i = 0; i < nthreads; i++) pthread_join(th[i], NULL);

    double cyc = CORES * GHZ * 1e9 * dt;              /* core-cycles in the window */
    if (test == T_FMA || test == T_KERNL1 || test == T_KERNSTREAM) {
        double macs;
        if (test == T_FMA) macs = (double)nthreads * iters * 24 * 16;
        else if (test == T_KERNL1) macs = (double)nthreads * (iters / DIMS) * DIMS * QROWS * 16;
        else macs = (double)nthreads * reps * (chunk / 16) * QROWS * 16;
        double fma_per_core_cycle = macs / 16 / cyc;
        printf("%-10s p=%d threads=%3d pf=%d  %.3f s  %7.1f GMAC/s  vpmadd231d per core-cycle %.3f",
               names[test], tpc, nthreads, prefetch, dt, macs / dt / 1e9, fma_per_core_cycle);
        if (test == T_KERNSTREAM) printf("  int8 read %.1f GB/s", (double)nthreads * reps * chunk / dt / 1e9);
        printf("\n");
    } else {
        double bytes = (double)nthreads * reps * chunk;
        printf("%-10s p=%d threads=%3d pf=%d  %.3f s  %7.1f GB/s read  (%.0f MB total per pass)\n",
               names[test], tpc, nthreads, prefetch, dt, bytes / dt / 1e9, (double)nthreads * chunk / 1e6);
    }
    return 0;
}
