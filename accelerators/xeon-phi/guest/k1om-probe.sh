#!/bin/bash
# Probe the MPSS k1om GNU toolchain: versions, IMCI in the assembler,
# intrinsics, auto-vectorization, and an OpenMP run on the card.
. /opt/mpss/3.8.6/environment-setup-k1om-mpss-linux
D=/root/k1om-probe; rm -rf $D; mkdir -p $D; cd $D

echo "== 1. versions"
$CC --version | head -1
${AS:-k1om-mpss-linux-as} --version | head -1
echo "CC=$CC"
echo "CFLAGS=$CFLAGS"

echo "== 2. IMCI in the assembler (one instruction per line)"
try() {
  printf '%s\n' "$1" > t.s
  if k1om-mpss-linux-as t.s -o t.o 2> err.txt; then
    printf 'OK    %-55s %s\n' "$1" "$(k1om-mpss-linux-objdump -d t.o | awk -F'\t' '/^ +0:/{print $3}')"
  else
    printf 'FAIL  %-55s %s\n' "$1" "$(head -1 err.txt | cut -c1-80)"
  fi
}
try 'vpmadd231d %zmm1, %zmm2, %zmm3'
try 'vpmadd231d (%rax){1to16}, %zmm1, %zmm2'
try 'vmovdqa32 (%rax){sint8}, %zmm0'
try 'vpmaxsd %zmm1, %zmm2, %zmm3'
try 'vpaddd %zmm1{cdab}, %zmm2, %zmm3'
try 'vpaddd %zmm1, %zmm2, %zmm3{%k1}'
try 'vpermd %zmm1, %zmm2, %zmm3'
try 'vprefetch0 (%rax)'
try 'vfmadd231ps (%rax){1to16}, %zmm1, %zmm2'
try 'vmovdqa32 (%rax){uint8}, %zmm0'

echo "== 3a. intrinsics headers"
INC=$($CC -print-file-name=include)
ls $INC | grep -iE 'mm|intrin' | tr '\n' ' '; echo
grep -l '_mm512_' $INC/*.h 2>/dev/null | head
grep -c '_mm512_' $INC/*.h 2>/dev/null | grep -v ':0' | head
cat > intr.c <<'C'
#include <immintrin.h>
__m512i f(__m512i a, __m512i b, __m512i c) { return _mm512_fmadd_epi32(a, b, c); }
C
if $CC $CFLAGS -O2 -c intr.c -o intr.o 2> intr.err; then echo "intrinsic _mm512_fmadd_epi32: compiles"; else echo "intrinsic _mm512_fmadd_epi32: FAIL: $(head -2 intr.err | tr '\n' ' ' | cut -c1-160)"; fi

echo "== 3b. auto-vectorization"
cat > vec.c <<'C'
void dot(const int *restrict a, const int *restrict b, int *restrict out, int n) {
  for (int i = 0; i < n; i++) out[i] += a[i] * b[i];
}
C
$CC $CFLAGS -O3 -S vec.c -o vec.s 2>&1 | head -3
echo "zmm references in -O3 output: $(grep -c zmm vec.s)"
$CC -Q --help=target 2>/dev/null | grep -iE 'knc|k1om|mic|512|vect' | head

echo "== 4. OpenMP program on the card"
cat > omp.c <<'C'
#include <omp.h>
#include <stdio.h>
#include <sched.h>
int main(void) {
  int n = 0, lo = 1 << 30, hi = -1;
  #pragma omp parallel reduction(+:n) reduction(min:lo) reduction(max:hi)
  { int c = sched_getcpu(); n = 1; lo = c; hi = c; }
  printf("threads=%d max_threads=%d cpus used %d..%d\n", n, omp_get_max_threads(), lo, hi);
  return 0;
}
C
$CC $CFLAGS -O2 -fopenmp omp.c -o omp && file omp | cut -c1-110
scp -q omp mic0:/tmp/omp && ssh mic0 'OMP_NUM_THREADS=240 GOMP_CPU_AFFINITY=1-240 /tmp/omp; ldd /tmp/omp | grep -iE "gomp|not found"'
