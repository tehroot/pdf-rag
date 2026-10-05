/* IMCI correctness test for the MaxSim inner loop on Knights Corner.
 * acc[i] += page[i] (int8, widened by {sint8} on load) * q (int32, {1to16} broadcast)
 * over 320 dims, 16 page rows in the lanes; compared with plain C. */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#define DIMS 320
#define LANES 16

int main(void) {
    //This is the page data. This is the INT8 page vectors.
    int8_t  *page;   /* [dim][row] */
    //This is the query data. This is the INT32 vectors composing the query tokens. In the test it's INT8 values
    //not INT32 (just for testing), these are INT8 values stored as INT32
    int32_t *q;
    //We use posix_memalign to allocate the page and query data memory layout in RAM so it's available to be populated
    //The start address for the 5120 byte range is 64 aligned. If memory allocation fails, a failure exit occurs
    if (posix_memalign((void **)&page, 64, DIMS * LANES) ||
        posix_memalign((void **)&q, 64, DIMS * sizeof(int32_t))) return 2;
    //We define ACC to be a 64 byte structure.
    int32_t acc[LANES] __attribute__((aligned(64)));
    //ref is 16 int32 values (they're initialized to 0 each)
    int32_t ref[LANES] = {0};
    srand(7);
    //iterate through all 5120 bytes that was posix_aligned earlier. We place a random INT8 there
    for (int i = 0; i < DIMS * LANES; i++) page[i] = (int8_t)(rand() % 255 - 127);
    //do the same thing as the previous line
    //where we generate a random INT32 value for the query at each array position
    for (int k = 0; k < DIMS; k++) q[k] = rand() % 255 - 127;
    //iterate through DIMS then
    for (int k = 0; k < DIMS; k++)
        //iterate through LANES then in the ref array we accumulate the result ahead of time?
        //accumulate and calculate AOT to compare output result from ASM
        //for each element pointed to by ref we place the dot product of page and q[k] in that spot (Q0 * P_r)
        //this is the summation result for one cell
        //page[k * LANES + r] is dimension k of FPpage vector r
        //ref holds holds 4 byte values (INT32) in each spot (see line 24),
        for (int r = 0; r < LANES; r++) ref[r] += page[k * LANES + r] * q[k];

    //asm keyword allows use of inline assembly
    //vpxord instruction, so we perform an XOR against zmm1 ourselves, and then store the result in zmm1
    //memory is a GCC-notifier to tell it that the assembly reads or writes memory and to not mess with it
    __asm__ volatile("vpxord %%zmm1, %%zmm1, %%zmm1" ::: "memory");
    //loop each dimension (all 320 of them)
    for (int k = 0; k < DIMS; k++) {
        __asm__ volatile(
            // vmovdqa32 in this context widens one dimension of the page vectors at a time, and moves them
            // into zmm0. So p0-p15, 0-319 for each slice of the full 320 dimension vector. p0-p15 is 16 bytes
            "vmovdqa32 (%0)%{sint8%}, %%zmm0\n\t"
            // in this context {1to16%} copies the memory value INT32 to all lanes
            // Q[k] is one dimension of a single query vector (0-319)
            // after that, vpmadd231d does a multiply-add using zmm0
            // against the value we widened. Then it get summed and put in zmm1.
            // so it's zmm0 containing p0-p15[k], vpmadd231d itself computes P_r[k] * Q[k]
            // where P_r[k] is the page vector 1 dimension * the Q[k] one dimension
            //vpmadd231d does 16 of these simultaneously, using P_0[k] ... P_15[k]
            // the result is added to the zmm1 register
            "vpmadd231d (%1)%{1to16%}, %%zmm0, %%zmm1"
            :: "r"(page + k * LANES), "r"(q + k) : "memory");
    }
    //vmovdqa32 does a final move where packed values in zmm1 are moved to the array in memory on the stack
    // acc is the destination. 16 lanes of zmm1 are written to acc as 64 bytes of INT32s.
    //After this instruction completes acc[r] holds the dot product for Q0 * P_r
    __asm__ volatile("vmovdqa32 %%zmm1, (%0)" :: "r"(acc) : "memory");

    //compares if there are any bad results between the pre-computed results and the results of inline assembly
    int bad = 0;
    for (int r = 0; r < LANES; r++) if (acc[r] != ref[r]) bad++;
    printf("lane0 imci=%d c=%d | lane15 imci=%d c=%d | mismatches=%d/%d -> %s\n",
           acc[0], ref[0], acc[15], ref[15], bad, LANES, bad ? "FAIL" : "PASS");
    return bad != 0;
}
