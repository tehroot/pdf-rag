# Xeon Phi (Knights Corner) as a machine

Written as a learning document: what the 7120P is, how it appears to a
host, how its software stack boots, and the programming rules that fall
out of the hardware. Numbers are for the 7120P (61 cores, 16 GB).

## 1. The silicon

**Cores.** 61 in-order, two-issue x86 cores derived from the 1994
Pentium P54C, with 64-bit and the vector unit added. Each core runs four
hardware threads, issued round-robin. An in-order core stalls on every
cache miss and every dependent instruction; the threads hide the stalls.
With one thread a core issues at most every other cycle; with two it can
issue every cycle; with three or four it stays busy through memory
waits. Rule: at least two threads per core, usually three or four, which
is why Linux on the card shows 244 CPUs. Clock 1.24 GHz.

**Vector unit.** One 512-bit VPU per core, 32 registers, its own
instruction set (IMCI, the ancestor of AVX-512, not compatible with it):
16 lanes of 32 bits, fused multiply-add, per-lane masks, gather and
scatter, and conversion on load so int8 and int16 in memory arrive as
int32 in registers. That last feature is what the MaxSim kernel wants:
int8 rows widen on the way in and multiply as int32. There is no SSE or
AVX; scalar x87 and the VPU only. A loop the compiler cannot vectorize
runs at scalar P54C speed. Vectorization is not an optimization here; it
is the difference between usable and useless.

**Caches and the ring.** 32 KB L1 data and a private 512 KB L2 per core,
no L3. Coherence is kept by tag directories distributed around a
bidirectional ring connecting all cores, the memory controllers and the
PCIe block. A core missing in its L2 asks the directory, which may
forward the line from another core's L2, so the 61 L2s act as a shared
30 MB cache with directory latency in the path. Data reused across cores
is fast; data streamed once is bandwidth-bound. The prefetch sweep is
the second kind, which is why the design batches queries and streams the
matrix once per batch.

**Memory.** 16 GB GDDR5 on 16 channels, 352 GB/s theoretical, 170-200
GB/s sustained for a streaming read. A sweep of the 9.6 GB matrix is
therefore 50-60 ms per batch, the floor the design plans around.

**PCIe.** A PCIe 2.0 ×16 endpoint with two BARs: a small one for control
registers and a large aperture through which the host reads and writes
the card's memory. The aperture is why "above 4G decoding" matters: it
does not fit the 32-bit MMIO window. A system-memory aperture in the
other direction lets the card DMA into host RAM. SCIF and the virtual
network are built on both.

## 2. How the host sees it

- **The `mic` driver** exposes `/dev/mic0`, maps the apertures and drives
  the DMA engines. It was removed from mainline Linux (5.x), hence the
  CentOS 7 guest in our bring-up.
- **SCIF** (Symmetric Communications Interface): a socket-like API over
  PCIe with endpoints on both sides (`scif_open`, `scif_connect`,
  `scif_send`) plus registered memory windows for RDMA. Several GB/s,
  microsecond latency; MPSS's own services use it.
- **The virtual Ethernet** `mic0` on both ends: an Ethernet device over
  shared rings, carrying ssh, NFS and any TCP service at a few hundred
  MB/s. The card becomes a host on a private link (172.31.1.1 and
  172.31.1.254 by default).

## 3. The card's operating system and boot

The card runs µOS, a small Linux (2.6.38-era kernel in MPSS 3.x) with a
k1om userland. It has no disk: its root filesystem is an initramfs built
by MPSS from a template plus overlay directories you configure, and
optionally an NFS mount from the host. Boot:

1. The host driver holds the card in reset and writes the µOS kernel and
   initramfs into card memory through the aperture.
2. It releases reset; the bootstrap jumps into the kernel; µOS brings up
   the 244 CPUs, the memory and the SCIF endpoint.
3. `mpssd` on the host sees the card, brings up `mic0` on both ends; the
   card starts `sshd`; `micctrl --status` reports online.
4. ssh in as root with the key MPSS baked into the image.

Every card reboot repeats this and forgets everything not in the image,
which is why the prefetch service reloads its matrix at start.

## 4. Programming models

| Model | Where code runs | Host needs | Use here |
|---|---|---|---|
| Offload | host program with `#pragma offload` regions; data pushed and pinned | process built with icc 2017 and linked to MPSS, living in the guest | the benchmark (`phi_maxsim.c` already written this way) |
| Native | binary cross-compiled with `-mmic` runs on the card's Linux; talks to the host over `mic0` or SCIF | MPSS for driver, image and virtual NIC; host side is a thin proxy | the service |
| Symmetric MPI | ranks on host and card | MPI with MIC support | not used |

Native mode for the service, because: the card owns its own memory
(host and proxy restarts do not drop the matrix); the frozen 2017
toolchain touches one binary instead of the whole service; a receive →
batch → sweep → reply loop is a loop, not a chain of offload regions;
and the card is debuggable with ssh, `top` and `gdb`. Costs: everything
is cross-compiled (keep the server to C, sockets, pthreads, OpenMP,
which the k1om root filesystem provides); µOS takes about 1 GB of the
16, leaving 9.6 GB matrix plus a few GB of headroom (about 400,000 more
pages); `mic0` exists on the machine owning the PCIe root, so the proxy
in the guest is the natural boundary.

## 5. Rules that follow from the hardware

- 122-244 OpenMP threads with affinity so threads of one core share its L2.
- Inner loops must vectorize to 16 lanes; read icc's vectorization report.
  Write the dot product as a reduction over a contiguous array (the
  kernel's `omp simd reduction`). Align the matrix base to 64 bytes;
  rows are 320 bytes, five vector loads each.
- Stream the matrix once per batch: put the queries in the inner position
  so each loaded page row is scored against every query in the batch
  while it is in registers. That reordering is the difference between one
  query per sweep and eight.
- No branches inside the vector loop; the max over rows is a vector max.
- Expect about 1 TMAC/s of int32 FMA across 61 cores, so 6 GMAC per query
  is about 6 ms of arithmetic. The sweep at 50-60 ms dominates, which is
  what makes batching pay: 8 queries per batch, about 70 ms per batch,
  50-100 q/s aggregate.

## 6. Reading

Intel, "Xeon Phi Coprocessor System Software Developer's Guide" (boot,
SCIF, driver); Jeffers & Reinders, "Intel Xeon Phi Coprocessor
High-Performance Programming" (core, VPU, thread rules, worked examples;
read this first); the MPSS user guide (`micctrl`, image build).
