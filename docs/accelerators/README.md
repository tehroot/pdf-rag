# Accelerators for the visual prefetch

Everything about running the first-pass page search (MaxSim over the
pooled page vectors) on something other than the ingest host's CPUs:
the service contract the backends share, the GPU backend, and the Xeon
Phi experiment. Kept in one place so the material does not scatter
across plans and component docs.

| Document | What it covers |
|---|---|
| [prefetch-service-contract.md](prefetch-service-contract.md) | The HTTP routes, the shared quantization rule, generations and fallback, how the ingest service calls it, how backends are tested against each other |
| [benchmarks.md](benchmarks.md) | Measured MaxSim numbers on the R530 Xeons, big-dumb's Ryzen, the A4500, the CMP 170HX, and the reference kernels |
| [xeon-phi-architecture.md](xeon-phi-architecture.md) | Knights Corner as a machine: cores, VPU, ring, memory, PCIe apertures, µOS, SCIF, and the programming rules that follow |
| [xeon-phi-bringup.md](xeon-phi-bringup.md) | Bring-up on the R530: riser, passthrough into a CentOS 7 guest, MPSS, toolchain, data, first runs; native mode for the service and why |

Context in the wider plans: step 6 of
[../plans/ingest-planner-v1.md](../plans/ingest-planner-v1.md) is where
the prefetch service sits in the ingest roadmap;
[../components/qdrant-segments-and-hnsw.md](../components/qdrant-segments-and-hnsw.md)
is why the CPU path is bound where it is.
