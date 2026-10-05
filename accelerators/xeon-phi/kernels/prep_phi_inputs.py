"""Quantize the exported pooled_rows to the per-page int8 layout the C kernel reads, and write the 50 queries.
Outputs: pooled_rows.i8 (N*32*320 int8), scales.f32 (N float32), queries.bin, and ids.npy is reused."""
import numpy as np, json, struct, sys
D="/srv/pdf-corpus/gpu-prefetch"; R="/srv/pdf-corpus/ingest/recall"
n=int(sys.argv[1]) if len(sys.argv)>1 else None
mm=np.load(f"{D}/pooled_rows.f32.npy", mmap_mode="r"); N=mm.shape[0] if n is None else min(n, mm.shape[0])
suffix="" if n is None else f".{N}"
with open(f"{D}/pooled_rows{suffix}.i8","wb") as fo, open(f"{D}/scales{suffix}.f32","wb") as fs:
    step=16384
    for s in range(0,N,step):
        x=np.asarray(mm[s:min(s+step,N)], dtype=np.float32)
        sc=np.maximum(np.abs(x).max(axis=(1,2)), 1e-8)/127.0
        q=np.rint(x/sc[:,None,None]).astype(np.int8)
        fo.write(q.tobytes()); fs.write(sc.astype(np.float32).tobytes())
qs=json.load(open(f"{R}/query_vectors.json"))
with open(f"{D}/queries.bin","wb") as fq:
    fq.write(struct.pack("i", len(qs)))
    for q in qs:
        Q=np.asarray(q["vectors"], dtype=np.float32); qsc=np.maximum(np.abs(Q).max(axis=1),1e-8)/127.0
        Qi=np.rint(Q/qsc[:,None]).astype(np.int8)
        fq.write(struct.pack("i", Q.shape[0])); fq.write(qsc.astype(np.float32).tobytes()); fq.write(Qi.tobytes())
print("wrote", N, "pages,", len(qs), "queries")
