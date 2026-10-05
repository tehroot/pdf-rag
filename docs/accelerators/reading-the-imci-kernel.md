# Reading the IMCI kernel

A guided reading of the Xeon Phi code in
[`accelerators/xeon-phi/`](../../accelerators/xeon-phi/), written to be read
slowly, with the code open beside it. Each part ends with questions to check
yourself.

| Part | Code | Status |
|---|---|---|
| 0. Concepts | — | written |
| 0.5. The math | — | written |
| 1. The smallest proof | `kernels/imci_test.c` | written |
| 2. The MaxSim step | `kern_step` in `kernels/phi_bench.c` | next |
| 3. 240 threads | `worker`, `pin`, the barriers in `phi_bench.c` | later |
| 4. The measurements | the test cases and `guest/run_bench.sh` | later |

---

## Part 0. Concepts

### Registers, lanes, and why 16

The Phi's vector unit works on **zmm registers**. There are 32 of them,
`zmm0` to `zmm31`. Each holds 512 bits. The kernel treats each register as
**16 lanes of 32 bits**: 16 independent integers side by side.

```
zmm0:  | lane 0 | lane 1 | lane 2 | ... | lane 15 |     16 × 32 bits = 512 bits
```

One vector instruction does the same operation on all 16 lanes in one go.
That is the whole source of the speed: one instruction, 16 results.

### Data types in this code

| Name | Size | Range | Where |
|---|---|---|---|
| `int8_t` | 1 byte | −128 … 127 | the page matrix in memory (quantized vectors) |
| `int32_t` | 4 bytes | ±2.1 billion | the query values, and everything inside registers |

The page data is stored as int8 to save memory and bandwidth (9.6 GB, not
38 GB as float). The vector unit cannot multiply int8, so each byte is
**widened to int32 while it is loaded**. That is "upconversion".

### Assembly syntax used here (AT&T, as GNU tools write it)

| Written | Meaning |
|---|---|
| `%zmm1` | register zmm1 |
| `(%rax)` | the memory at the address held in register `rax` |
| `16(%rax)` | the memory 16 bytes after that address |
| `op  A, B, C` | **sources first, destination last** (the reverse of Intel's manuals) |
| `{sint8}` | after a memory operand: widen signed int8 → int32 on load |
| `{1to16}` | after a memory operand: read one 32-bit value, copy it to all 16 lanes |
| `{%k1}` | after the destination: write only the lanes whose bit in mask `k1` is set |

### Assembly inside C (GCC "inline asm")

```c
__asm__ volatile("instructions" : outputs : inputs : clobbers);
```

- `%0`, `%1` are **placeholders** for the inputs, in order. With
  `"r"(page + k * LANES)`, GCC puts that address in some register (for
  example `rbx`) and writes it where `%0` is.
- Because `%` marks placeholders, a real register needs two: `%%zmm1`.
- Because `{` and `}` also have a meaning to GCC, IMCI braces need a `%`:
  `%{sint8%}`.
- `volatile` and the `"memory"` clobber stop GCC from moving, merging or
  deleting the block.
- `\n\t` separates instructions inside one string.

---

## Part 0.5. The math: what one query costs

### The two inputs

| Item | Shape | Where it comes from |
|---|---|---|
| Query Q | N vectors × 320 numbers | The sidecar embeds the search text: one vector per token |
| Page P (`pooled_rows`) | 32 vectors × 320 numbers | The model makes up to 1,280 vectors per page image; they are pooled into 32 buckets |

Every vector has 320 numbers, the model's embedding size. N depends on the
query text: the 50 benchmark queries (4–8 words) give 14–20 vectors, because
about 9–10 tokens are fixed overhead (the model's prompt template and
special tokens). A 50-word question gives an estimated 70–80. The sidecar
sets no length limit. The examples below use N = 20.

### MaxSim: the score of one page

```
              p0    p1   ...   p31        ← 32 columns: the page's 32 vectors
        q0  [ s     s    ...    s  ]  → max → m0
        q1  [ s     s    ...    s  ]  → max → m1
        ...
        q19 [ s     s    ...    s  ]  → max → m19

        score(page) = m0 + m1 + ... + m19
```

Each cell `s` is the **dot product** of one query vector and one page
vector. Each query vector keeps only its best match on the page (the max of
its row); the page's score is the sum of those best matches.

### One cell is 320 multiply-adds

Q0 and P1 are vectors of 320 numbers each, so one cell is a sum of 320
products:

```
cell (Q0, P1) = Q0[0]·P1[0] + Q0[1]·P1[1] + ... + Q0[319]·P1[319]
```

Small example with 4 dimensions in place of 320:

```
Q0 = [ 3, -2,  5,  1]
P1 = [ 4,  7, -1,  2]
Q0·P1 = 3·4 + (-2)·7 + 5·(-1) + 1·2 = 12 − 14 − 5 + 2 = −5
```

A **multiply-add (MAC)** is one multiplication added into a running sum. It
is one instruction step on the hardware, so all the cost figures count MACs.

| Level | Results | MACs |
|---|---|---|
| One cell | 1 | 320 |
| One row (one query vector, 32 page vectors) | 32 | 32 × 320 = 10,240 |
| One page (the 20 × 32 table) | 640 | 640 × 320 = **204,800** |
| All 934,834 pages, one query | ≈ 598 million | ≈ **191 billion (191 GMAC)** |

Keep two numbers apart: **640 results (cells) per page**, and **320 MACs
inside each result**.

### Types: int8 in, int32 out

| Value | Largest size | Type |
|---|---|---|
| One page number, one query number | 127 | int8 range |
| One product | 127 × 127 = 16,129 | fits int16 |
| One cell (sum of 320 products) | 320 × 16,129 = 5,161,280 | **int32** |

So each row of the table is 32 int32 values. For one page:

```
1. 640 dot products            → 640 × int32        (the table)
2. max of each row (32 → 1)    → 20 × int32
3. × query-row scale, sum      → 1 float
4. × page scale                → 1 float: the page's score
```

The int8 values are scaled-down versions of the real vectors (real ≈ int8 ×
scale). The page scale is the same for all 32 page vectors and the query
scale is the same along one row, so neither changes which cell wins a max.
Both are applied after the integer work: 204,800 integer MACs, then 21
float multiplications per page.

### The query length sets the cost

One page costs N × 32 × 320 MACs, so the cost grows in proportion to N:

| Query vectors N | Per query, whole corpus | Phi at 550–740 GMAC/s (measured kernel rates) |
|---|---|---|
| 20 | 191 GMAC | ≈ 260–350 ms |
| 40 | 383 GMAC | ≈ 0.5–0.7 s |
| 75 | 718 GMAC | ≈ 1.0–1.3 s |

The kernel also keeps one accumulator register per query vector (20 of
them). With more than about 24 query vectors it must work in groups of 20
and read each page once per group.

### How the code maps onto the table

| Code | Part of the table |
|---|---|
| `imci_test.c` (Part 1) | 1 query row × 16 page vectors: 16 cells |
| `kern_step` (Part 2) | 20 query rows × 16 page vectors: 320 cells, one dimension per call; 320 calls complete them |
| The full kernel (to write) | Both halves of the page (vectors 0–15 and 16–31), the row maxima, the sum |

In `kern_step` the number 320 appears twice by coincidence: 20 × 16 = 320
cells, and 320 calls (one per dimension). They are different quantities.

---

## Part 1. The smallest proof: `kernels/imci_test.c`

**Goal:** prove that the card can do the kernel's two key instructions
correctly, by comparing them with plain C on the same data.

**The computation:** 16 page rows, 320 dimensions, one query row:

```
for each page row r (0..15):   ref[r] = Σ over k (0..319) of page[k][r] × q[k]
```

That is 16 dot products of length 320.

### 1.1 Memory layout: dimension first

```c
int8_t  *page;   /* [dim][row] */
int32_t *q;
posix_memalign((void **)&page, 64, DIMS * LANES)
```

The page data is stored **dimension-major**: first the 16 rows' values of
dimension 0, then the 16 rows' values of dimension 1, and so on.

```
address:  0 ............. 15 | 16 ............ 31 | 32 ...
content:  dim 0, rows 0..15  | dim 1, rows 0..15  | dim 2, ...
```

So the 16 bytes of one dimension are **next to each other**, and one load
can bring all 16 rows of that dimension into the 16 lanes. This layout is
the key decision of the whole kernel (see Part 2).

`posix_memalign(…, 64, …)` places the buffer on a 64-byte boundary. The
`vmovdqa32` load ("a" = aligned) requires its source to be aligned; with
`{sint8}` the 16-byte source must be on a 16-byte boundary, which
`k * 16` keeps.

### 1.2 Test data and the reference answer

```c
for (int i = 0; i < DIMS * LANES; i++) page[i] = (int8_t)(rand() % 255 - 127);
for (int k = 0; k < DIMS; k++) q[k] = rand() % 255 - 127;
for (int k = 0; k < DIMS; k++)
    for (int r = 0; r < LANES; r++) ref[r] += page[k * LANES + r] * q[k];
```

Random values in −127 … 127 (the range of the real quantized data). The
double loop is the **reference**: the same dot products in plain C, one
multiply at a time. `page[k * LANES + r]` reads dimension `k`, row `r`.

### 1.3 Zero the accumulator

```c
__asm__ volatile("vpxord %%zmm1, %%zmm1, %%zmm1" ::: "memory");
```

`vpxord A, B, C` computes C = A XOR B in all 16 lanes. A value XOR itself is
0, so this sets all 16 lanes of `zmm1` to zero. `zmm1` will collect the 16
sums.

### 1.4 The loop: one load, one multiply-add per dimension

```c
for (int k = 0; k < DIMS; k++) {
    __asm__ volatile(
        "vmovdqa32 (%0)%{sint8%}, %%zmm0\n\t"
        "vpmadd231d (%1)%{1to16%}, %%zmm0, %%zmm1"
        :: "r"(page + k * LANES), "r"(q + k) : "memory");
}
```

For one dimension `k`:

```
memory at page + k*16:   [ p0  p1  p2 ... p15 ]        16 bytes, int8
                            │ vmovdqa32 … {sint8}: widen each byte to 32 bits
                            ▼
zmm0:                    | p0 | p1 | p2 | ... | p15 |   16 × int32

memory at q + k:         [ qk ]                         4 bytes, int32
                            │ {1to16}: copy to all lanes
                            ▼
                         | qk | qk | qk | ... | qk |

vpmadd231d:  zmm1 = zmm1 + zmm0 × (qk in all lanes)
zmm1:                    | Σ p0·q | Σ p1·q | ... | Σ p15·q |
```

After the 320 iterations, lane `r` of `zmm1` holds exactly `ref[r]`: the
dot product of page row `r` with the query.

**The name `vpmadd231d`:** `v` = vector, `p` = packed (all lanes), `madd`
= multiply-add, `d` = doublewords (32-bit integers). `231` gives the
operand roles: operands 2 and 3 are multiplied, and the product is added to
operand 1, which is also the destination. In other words: the destination
is the running sum.

**Two instructions, 16 multiply-adds.** The C reference does 16 separate
multiplies and 16 additions for each `k`.

### 1.5 Store and compare

```c
__asm__ volatile("vmovdqa32 %%zmm1, (%0)" :: "r"(acc) : "memory");
...
for (int r = 0; r < LANES; r++) if (acc[r] != ref[r]) bad++;
```

The plain `vmovdqa32` (no conversion) writes the 16 int32 lanes to the
array `acc`. Then each lane is compared with the C answer. The run on the
card printed:

```
lane0 imci=109678 c=109678 | lane15 imci=13783 c=13783 | mismatches=0/16 -> PASS
```

### 1.6 One weakness of this test program (on purpose small)

`zmm0` and `zmm1` keep their values **between** the separate `__asm__`
statements of the loop. That works only because this GCC never uses zmm
registers itself (it does not vectorize for the card). A compiler that used
them could overwrite `zmm1` between iterations. Production code keeps the
whole loop inside one `__asm__` block, or declares the registers it uses.

### Check yourself

1. Why does the page buffer have the dimension first, not the row first?
   What would one load bring into the 16 lanes with the row-first layout?
2. Why does the query value need `{1to16}`, but the page data does not?
3. After the loop, what does lane 7 of `zmm1` contain, in words?
4. Why can the C reference use `int32_t` sums without overflow? (Hint:
   the largest possible value is 320 × 127 × 127.)
5. What would break if `q` were stored as `int8_t` instead of `int32_t`?
   (Hint: look at what `{1to16}` reads.)

### Answers worked through (2026-10-05)

Your answers, as given, with the correction or addition that followed.

**1. Row first instead of dimension first.**
- Your first answer: "It would bring in the first dimension of every row to
  be computed against Q[k]."
- Correction: that is what the **dimension-first** order gives. With row
  first, the first 16 bytes are `P0[0] P0[1] … P0[15]`: dimensions 0–15 of
  a **single** page vector.
- Your second answer: "It's trying to compute the query over a single page,
  rather than all of the pages."
- Addition: a single page **vector** (P0). The real problem is the
  multiplication: the 16 lanes hold 16 different dimensions, and each needs
  its own query value (`P0[0] × Q0[0]`, `P0[1] × Q0[1]`, …). A broadcast puts
  one value in all lanes, so 15 of the 16 products would be wrong. Loading
  `Q0[0 … 15]` as a vector fixes the products, but then all 16 lanes belong
  to **one** dot product and must be added **across** the lanes at the end,
  which is slow on KNC. With dimension first, each lane is its own dot
  product from start to end.

**2. Why `{1to16}` for the query, not the page data.**
- Your answer: "The fanout happens where Q[0] gets multiplied against the 16
  page vectors P_0[0] P_1[0] P_2[0], P_r[k]. {1to16} replicates it to all 16
  lanes of the vector processor."
- Correct. Addition: the page data needs no broadcast because the lanes must
  hold 16 **different** values, `P_0[k] … P_15[k]`, and the dimension-first
  order already stores them next to each other, so a normal load fills the
  lanes.

**3. What lane 7 holds after the loop.**
- Your answer: "Lane 7 would contain the dot product of Q[0] * P_7."
- Correct: **Q0 · P_7**, the sum of `P_7[k] × Q0[k]` over all 320
  dimensions. Notation: **Q0** is the query vector; **Q0[0]** (`q[0]` in the
  code) is only its dimension 0.

**4. Why int32 sums cannot overflow.**
- Your answer: "Int32 is much wider, so there's no overflow possibility."
- Correct. With numbers: the largest possible sum is 320 × 127 × 127 =
  5,161,280; the int32 limit is 2,147,483,647, about 400 times larger.

**5. What breaks if `q` is `int8_t`.**
- Your answer: "I assume the thing is that because the vector instruction
  requires a 32 bit wide integer that we can't just use INT8s."
- Close. The exact reason: `{1to16}` reads **4 bytes** at the address and
  copies them to all lanes. With `int8_t`, `q + k` points at one byte, and
  the broadcast would read `q[k] … q[k+3]` together as one meaningless int32.
  A memory operand also takes only one modifier, so `{1to16}` and `{sint8}`
  cannot be combined to widen and broadcast a single byte.

**Open for later:** the mechanics of **why** a sum across the lanes of one
register is slow on this vector unit (question 1).
