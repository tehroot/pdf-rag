"""Score the C kernel's top-k page indices against the exact ground truth (recall@50/100, top-1)."""
import numpy as np, json, sys, statistics
D="/srv/pdf-corpus/gpu-prefetch"; R="/srv/pdf-corpus/ingest/recall"
ids=np.load(f"{D}/ids.npy", allow_pickle=False); qs=json.load(open(f"{R}/query_vectors.json")); gt=json.load(open(f"{R}/ground_truth.json"))
r50=[];r100=[];t1=[];ms=[]
for line in open(sys.argv[1]):
    if not line.startswith("query"): continue
    head,rest=line.split(":",1); qi=int(head.split()[1]); ms.append(float(head.split()[2]))
    got=[ids[int(x)] for x in rest.split()]; truth=[x[0] for x in gt[qs[qi]["query"]]["pooled_rows"]]
    r50.append(len(set(got[:50])&set(truth[:50]))/50); r100.append(len(set(got[:100])&set(truth))/100); t1.append(float(got[0]==truth[0]))
print(json.dumps({"queries":len(ms),"top1":round(statistics.mean(t1),3),"recall@50":round(statistics.mean(r50),4),"recall@100":round(statistics.mean(r100),4),"ms_p50":round(statistics.median(ms),1)}))
