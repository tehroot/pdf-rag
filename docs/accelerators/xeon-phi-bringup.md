# Xeon Phi 7120P bring-up on the R530

**Status 2026-10-01: the card is online.** It boots µOS inside a CentOS 7.9
VM on huge-dumb through VFIO passthrough, and IMCI code built with the free
MPSS GNU toolchain runs on it and matches C exactly. Microbenchmarks are in
[benchmarks.md](benchmarks.md#xeon-phi-7120p). Scripts, kernels and raw
results are in [`accelerators/xeon-phi/`](../../accelerators/xeon-phi/).

The original plan (riser, Intel Parallel Studio, OpenMP offload) changed in
several places. This document records the path that worked and the traps
found on the way.

## Hardware path that works

| Item | State |
|---|---|
| Host | huge-dumb, R530 chassis service tag 148XV52 (the original chassis lost PSU detection on 2026-09-30 and was replaced; same disks and OS) |
| Slot | PCIe slot 2 (CPU root port `00:02.0`, Gen3 ×16) through an ×16 riser, lid off |
| Link | **Gen2 ×4**, zero AER errors. The riser is rated ×16 electrical but trains ×4; accepted for now, to be revisited on the C240 M5 |
| BARs on the host | BAR 0: 16 GB, 64-bit prefetchable, above 4 GB; BAR 4: 128 KB |
| IOMMU group | the card alone (group 51) |
| Aux power | 6-pin + 8-pin from the external dock's ATX PSU (PCIe cables); that PSU is switched on before the server |
| Cooling | a fan blowing along the fins toward the bracket. Idle: die 47 °C, 100 W. Microbenchmarks: die maximum 52 °C, 115 W |

### Traps

- **Never power the card from a Dell PIB 8-pin header.** The R530 PIB's spare
  8-pin connectors are not wired as PCIe 8-pin (Dell uses an EPS-style
  arrangement on such headers). With them the card was warm and its
  presence was detected (`PresDet+`) but the link never trained (width
  ×0), in the dock and in a direct CPU slot alike. With the dock PSU's PCIe
  cables it enumerated at once.
- **The OCuLink dock in slot 5 did not link the card.** Slot 5 is a chipset
  (PCH) port, Gen2 ×4 (`00:1c.4`), the same path on which the A4500 trained
  only Gen1 ×4. A forced Gen1 retrain did not help. Not retried with
  correct power; slot 2 on a riser works.
- **The R530 riser's top ×16 slot cannot take the card with the lid on**
  (the passive cooler is too tall). Lid off with a fan, for now.

## Software path that works

```
Debian 13 host ──vfio-pci──▶ VM phi-host (CentOS 7.9, kernel 3.10.0-1160.el7)
                                   └── MPSS 3.8.6 ──mic0──▶ card µOS (172.31.1.1)
```

1. **Host** (`accelerators/xeon-phi/host/host-setup.sh`): qemu 10.0,
   libvirt 11.3, OVMF; `vfio-pci` claims `8086:225c` (`/etc/modprobe.d/vfio-phi.conf`);
   `libvirt-docker-forward.service` adds ACCEPT rules for `virbr0` to
   Docker's `DOCKER-USER` chain, because Docker's `FORWARD DROP` policy
   blocks the VM's NAT traffic.
2. **VM** (`vm-install.sh`, `phi-ks.cfg`): q35, OVMF (UEFI, no Secure Boot),
   16 GB, 8 vCPUs, 60 GB disk at `/srv/vm/phi-host.qcow2`, NAT
   (192.168.122.35). OVMF's 64-bit PCI window raised to 64 GB
   (`-fw_cfg opt/ovmf/X-PciMmio64Mb=65536`) for the 16 GB BAR. Unattended
   CentOS 7.9 install from `vault.centos.org`; the kernel stays at the GA
   `3.10.0-1160.el7` (`exclude=kernel*` in `yum.conf`).
3. **Passthrough** (`phi-attach.sh`): the card is attached after the
   install. In the guest it appears at `07:00.0` with the same 16 GB BAR,
   memory and bus master enabled. **VFIO risk cleared:** MSI-X, DMA and the
   card boot all work.
4. **MPSS** (`mpss-fetch.sh`): stock Intel MPSS 3.8.6 from archive.org item
   `intel-mpss-3.8.6` (`mpss-3.8.6-linux.tar`, MD5
   `8ad2ecfadec5f12213276fc0b588f466`), plus the community
   `mpss-modules-3.10.0-1160.el7.x86_64-3.8.6-4` from
   [github.com/jjkeijser/mpss](https://github.com/jjkeijser/mpss) (release
   `RHEL7.9`), because Intel's module RPMs stop at kernel 3.10.0-693.
   Install every top-level RPM except `mpss-micsmc-gui` and
   `mpss-eclipse-cdt-mpm`, then **`depmod -a`** (the RPM does not run it),
   `modprobe mic`.
5. **First boot** (`guest/phi-boot-guarded.sh`): `micctrl --initdefaults`,
   `systemctl start mpss`, with a guard that shuts the card down at a die
   temperature of 85 °C. Result: `mic0: online`.

Card facts from `micinfo`: flash 2.1.02.0391, SMC firmware 1.17.6900,
SKU C0PRQ-7120 P/A/X/D, stepping C0, 1.238 GHz, 15,872 MB GDDR5 at
5.5 GT/s, µOS kernel 2.6.38.8+mpss3.8.6, 244 logical CPUs. No firmware
update was needed.

## Access

| From | Command |
|---|---|
| huge-dumb (`markcardish`) | `ssh mic0` (ProxyJump through `phi-host`; see `~/.ssh/config`) |
| huge-dumb | `ssh phi-host` (the VM, as root) |
| the VM | `ssh mic0`; serial console `screen /dev/ttyMIC0` |
| huge-dumb | `sudo virsh console phi-host` (VM serial console) |
| huge-dumb | `phi-cores [-i S] [-w] [-v]`: load grid of the 244 threads (`host/phi-cores`) |

Card CPU numbering: logical CPUs `4c+1 .. 4c+4` are core `c` (0–59); CPUs
0, 241, 242, 243 are core 60, where µOS runs. Compute threads go on CPUs
1–240.

The card's root file system lives in its RAM. Persistent changes go in
`/var/mpss/mic0/` in the VM (for example
`/var/mpss/mic0/root/.ssh/authorized_keys`), which MPSS copies to the card
at every card boot.

## Toolchain: the free MPSS GNU SDK

No licensed Intel compiler is available. Intel's KNC compilers are
commercial; oneAPI no longer supports KNC; a copy of Parallel Studio XE
2016 found on archive.org is the Windows edition and contains a crack, and
is not used. Mainline GCC's "Intel MIC" support (removed in GCC 13) was
OpenMP offload to Knights Landing, never Knights Corner.

What the MPSS SDK (`mpss-sdk-k1om`, in `/opt/mpss/3.8.6`) does
(`guest/k1om-probe.sh`):

| Question | Answer |
|---|---|
| Versions | k1om GCC 5.1.1 (default `-march=knc`), binutils 2.22.52 |
| IMCI in the assembler | **Yes, with `-march=k1om`**: `vpmadd231d`, `{1to16}`, `{sint8}`/`{uint8}`, `vpmaxsd`, swizzles, masks, `vpermd`, `vprefetch0` all encode correctly |
| IMCI intrinsics | No (its headers are KNL AVX-512) |
| Auto-vectorization for the VPU | No |
| OpenMP | No `omp.h` or `libgomp` in the SDK, on the card, or in `mpss-3.8.6-k1om.tar`; use pthreads (card glibc 2.21). `libgomp` could be cross-built from `gcc-5.1.1+mpss3.8.6` in `mpss-src-3.8.6.tar` |

Build line:

```bash
. /opt/mpss/3.8.6/environment-setup-k1om-mpss-linux
k1om-mpss-linux-gcc $CFLAGS -O2 -Wa,-march=k1om prog.c -o prog -lpthread
```

In GCC inline assembly, IMCI braces must be escaped: `%{sint8%}`,
`%{1to16%}`. `kernels/imci_test.c` (a `{sint8}` load and `vpmadd231d` with
a `{1to16}` broadcast, 16 lanes × 320 dims) matches C exactly on the card.

## Kernel and harness

- `kernels/phi_maxsim.c`: the int8 MaxSim kernel validated on the host
  Xeons (2026-09-29): top-1 0.98, recall@50 0.9948, recall@100 0.992,
  1,484.6 ms per query (`results/host-xeon-e5-2650v3-2026-09-29.out`).
  Its card build assumed icc and OpenMP offload; the card port uses
  pthreads and an IMCI inner loop instead.
- `kernels/prep_phi_inputs.py [N]`: writes `pooled_rows.i8` (9.6 GB),
  `scales.f32` and `queries.bin` under `/srv/pdf-corpus/gpu-prefetch/`.
- `kernels/score_phi.py <out>`: recall@50/100 and top-1 against
  `/srv/pdf-corpus/ingest/recall/ground_truth.json`.
- `kernels/phi_bench.c` + `guest/run_bench.sh`: the microbenchmarks.

## Next

1. Find the 35 % gap between the FMA peak and the MaxSim step in L1
   (variants: register operands, a plain load in place of `{sint8}`).
2. Tune the streaming step (prefetch distance, two streams per thread) to
   reach the L1 rate.
3. Port `phi_maxsim.c` to pthreads + the IMCI step with the dim-major page
   layout, load the 9.6 GB matrix into card memory, score against the
   ground truth.
4. Then the native prefetch service behind
   [prefetch-service-contract.md](prefetch-service-contract.md).

## Risks, in order

1. ~~MPSS under VFIO~~: cleared 2026-10-01.
2. ~~Toolchain availability~~: cleared for native code (GNU SDK + IMCI
   assembly); no OpenMP and no vectorizing compiler.
3. Thermal: open-air fan only; long full-load runs not yet tested. The
   guard scripts read `micsmc`.
4. Support horizon: µOS, MPSS 3.8.6 and the 2012 binutils are frozen. The
   Phi stays one backend behind a contract the GPU also implements.
