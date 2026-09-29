# Xeon Phi 7120P bring-up on the R530

Card ordered 2026-09-24, arrived 2026-09-29. Riser being sourced. This
is the sequence, what each step proves, and what can go wrong. The
kernel and harness are already validated on the host CPUs.

## What is verified on the R530 (2026-09-24)

| Requirement | State |
|---|---|
| Large BARs above 4 GB | enabled and in use: the root bus has a 256 GB MMIO window at 0x380_0000_0000 and the A4500's 64-bit BARs already live there |
| Free ×16 slot | slot 2: PCIe 3, ×16 electrical, full-length, empty. Earlier POST failures in that slot were bifurcating NVMe carriers (the R530 BIOS has no bifurcation); a single-endpoint card is a different case |
| IOMMU for passthrough | on, 85 groups, no kernel parameter needed |
| KVM | `/dev/kvm` present, 80 VT-x threads; qemu and libvirt not yet installed |
| Power | 140 W draw on two redundant supplies; the card adds up to 300 W |
| Physical | the 2U chassis does not take the card in slot 2 as mounted → riser |

## Riser

- A shielded PCIe 3.0 ×16 extender (300-600 mm) out of slot 2 keeps the
  full link; OCuLink works too (×4 is enough for this traffic).
- Power: 75 W from the slot plus a 6-pin and an 8-pin, up to 300 W. The
  board the card seats in must feed the slot's 75 W as well as the aux
  connectors; the A4500's dock is the known-good pattern.
- Cooling: the 7120P is passive. A shroud with a 92-120 mm fan pushing
  air along the fins is mandatory; it throttles within a minute and shuts
  down under load without it.

## Sequence

1. **Physical.** Card on the riser, both aux connectors and slot power,
   the shroud fan running before power-on.
2. **Enumeration on the host.** `lspci` shows "Intel Xeon Phi coprocessor
   SE10/7120"; `lspci -vv` shows the two BARs with the large one assigned
   above 4 GB. "Unassigned" on the large BAR means the window, a BIOS
   matter, not a driver one.
3. **Isolate for passthrough.** Note the IOMMU group under
   `/sys/kernel/iommu_groups`, bind to `vfio-pci` by vendor:device id,
   confirm nothing else shares the group.
4. **Guest.** qemu, q35 machine, OVMF firmware with above-4G decoding on
   in the guest firmware, `-device vfio-pci` for the card, 16 GB RAM, 8
   vCPUs, CentOS 7.9. Inside, `lspci` must show the same BAR sizes. This
   is the risk point: MPSS under VFIO works in reports worth trusting but
   was never a supported configuration. Fallback: bare-metal CentOS 7 on
   a spare disk in the R530 for the duration of the test.
5. **MPSS 3.8.6** in the guest: install the RPMs, build `mpss-modules`
   against the guest kernel, `micctrl --initdefaults` (bakes the ssh key
   and network config into the card image), `systemctl start mpss`,
   `micctrl --status` until online. `micinfo` (firmware, memory, cores),
   `micsmc` (temperature, power), `miccheck` (self-tests).
6. **Firmware.** `micflash` if the card's flash is older than MPSS
   expects; the tool says so. Once, with the card idle.
7. **Toolchain.** Intel Parallel Studio 2017/2018 with the MIC target in
   the guest (`source compilervars.sh intel64`). It is no longer on
   Intel's site; archives only. Without it there is no offload and no
   native build (a GCC k1om toolchain is a rebuild from old sources).
   Build two ways: `icc -qopenmp -qoffload phi_maxsim.c` (offload
   benchmark) and `icc -mmic -qopenmp` (native binary);
   `micnativeloadex` copies and runs a native binary with its libraries.
8. **Data.** The card's RAM is its only storage, so NFS-export the guest
   directory holding the matrix and mount it on the card, rather than
   copying 9.6 GB into tmpfs beside the running process.
9. **Run and observe.** Offload benchmark first, then the native binary;
   watch `micsmc` for temperature and `top` on the card for thread
   utilization; read icc's vectorization report to confirm the inner
   loop hit the VPU. Score with `score_phi.py` against the same ground
   truth as the GPU runs.

## Kernel and harness (ready, validated 2026-09-29)

`/srv/pdf-corpus/phi/` on the R530:

- `phi_maxsim.c`: int8 MaxSim, OpenMP across pages, SIMD dot products,
  per-page scale factored out; `#pragma offload` regions guarded by
  `__INTEL_OFFLOAD` so the same source builds for the host with
  `gcc -O3 -march=native -fopenmp` and for the card with icc. On the card
  the matrix transfers once and stays resident across queries.
- `prep_phi_inputs.py [N]`: writes `pooled_rows.i8` (9.6 GB), `scales.f32`
  and `queries.bin` under `/srv/pdf-corpus/gpu-prefetch/` from the
  existing export.
- `score_phi.py <out>`: recall@50/100 and top-1 against
  `/srv/pdf-corpus/ingest/recall/ground_truth.json`.

Host validation on the Xeons, full corpus, 40 threads: top-1 0.98,
recall@50 0.9948, recall@100 0.992, identical to the GPU per-page int8
result, at 1,485 ms per query. That is the Phi's baseline to beat and
the proof the kernel computes the right thing.

## After the benchmark: the native service

The same kernel wrapped in a socket loop, compiled `-mmic`, with the
batch reordering (queries in the inner position, matrix streamed once per
batch). The proxy in the guest implements the contract in
[prefetch-service-contract.md](prefetch-service-contract.md): query
quantization, id table, generation, `/v1/append` and `/v1/reload` (matrix
push over `mic0` TCP; SCIF if the minutes matter), `/v1/status` with the
card's temperature from `micsmc`. Transport for queries: fixed-size
binary records over `mic0` TCP, 6 KB in, about 2 KB out.

## Risks, in order

1. MPSS under VFIO (step 4); fallback bare metal.
2. Toolchain availability (step 7).
3. Thermal on the riser; the status route carries the temperature.
4. Support horizon: µOS, MPSS 3.8 and icc 2017 are frozen. The Phi stays
   one backend behind a contract the GPU also implements.
