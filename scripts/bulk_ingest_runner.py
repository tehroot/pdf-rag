#!/usr/bin/env python3
"""Bulk directory-ingest runner for pdf-rag-http (resumable, no server state).

Feeds a directory tree of PDFs into a knowledge base through
POST /ingest/directory in fixed-size batches, several batches in flight at
once, and keeps going until every file is accounted for. What "accounted
for" means is read from the live system, not from local state, so the
runner can be killed and restarted at any time:

  a file is done when it has page points in <kb>_pages, OR a visual job
  queued / in progress (submitted, pages not yet embedded). Chunk points
  alone do NOT count: the text side is written before the visual job runs,
  so "has chunks" also describes a document whose visual job failed for
  good (24 DTIC files were missed that way on 2026-09-19). For a knowledge
  base with the visual index off, pass --text-only and chunks are the test.

Each batch is staged as a directory of relative symlinks under --stage,
named uniquely per batch (a reused directory would re-ingest its previous
contents), and posted with enable_visual_index=true. Batches must finish
inside the server's HTTP idle timeout (Quarkus default 30 min), which is why
they are small; --max-time is the client-side cap.

Failure policy: a file whose response entry is an error is retried on later
loops; after --skip-after errors it is skipped and listed in the log. A cut
connection makes the runner wait until the KB's document count is stable
(the server finishes the in-flight batch on its own) before continuing.

Sizing on the R530 (2026-09-18): --batch 40 --concurrent 3 gives 12-way text
ingest against the server's 4 files per call; the text embedder needs
LLAMA_PARALLEL=8 or the text threads queue on its slots. Pause the runner
during service recreations with `kill -STOP <pid>` / `kill -CONT <pid>`
(find the pid with pgrep -xf on the exact command line — never pkill -f a
pattern that appears in your own shell's command line).

Typical invocation (the DTIC load on the R530):

  nohup python3 scripts/bulk_ingest_runner.py \
      --kb dtic_archive --root /tank/documents/archive-org-dtic \
      --glob 'pdf/archive.org/download/DTIC_*/DTIC_*.pdf' \
      --stage /tank/documents/archive-org-dtic/ingest/r \
      --out /srv/pdf-corpus/ingest \
      --metadata '{"source":"archive.org dticarchive"}' \
      --wait-for-process 'crawl-seeds' > runner.out 2>&1 &

--root must be visible to the pdf-rag-http container under the same path
prefixed with /host (INGEST_HOST_ROOT=/), because the server reads the
staged symlinks in place; nothing is uploaded.
"""
import argparse, collections, glob, json, os, subprocess, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--kb", required=True, help="knowledge base name")
ap.add_argument("--root", required=True, help="corpus root on the server host")
ap.add_argument("--glob", default="**/*.pdf", help="glob under --root selecting the PDFs (default **/*.pdf)")
ap.add_argument("--stage", required=True, help="directory for per-batch symlink dirs (must be under a path the container sees as /host/...)")
ap.add_argument("--out", required=True, help="directory for run.log, per-batch JSON responses, failures.jsonl")
ap.add_argument("--base", default="http://127.0.0.1:8080", help="pdf-rag-http base URL")
ap.add_argument("--qdrant", default="http://127.0.0.1:6333", help="Qdrant REST URL (read-only scrolls)")
ap.add_argument("--batch", type=int, default=40); ap.add_argument("--concurrent", type=int, default=3)
ap.add_argument("--max-time", type=int, default=1700, help="client cap per batch call, seconds (server idle timeout is 1800)")
ap.add_argument("--skip-after", type=int, default=2, help="skip a file after this many error responses")
ap.add_argument("--metadata", default="{}", help="JSON object merged into every page/chunk payload")
ap.add_argument("--wait-for-process", default="", help="pgrep -f pattern; while it runs, keep looping for new files instead of exiting")
ap.add_argument("--text-only", action="store_true", help="the KB has no visual index: a file is done when it has chunk points (default: page points)")
a = ap.parse_args()

BASE, QD, KB = a.base, a.qdrant, a.kb
ROOT, STAGE, OUT = a.root, a.stage, a.out
BATCH, MAX_TIME, SKIP_AFTER, CONCURRENT = a.batch, a.max_time, a.skip_after, a.concurrent
META = json.loads(a.metadata)
GLOB = a.glob; WAIT_PROC = a.wait_for_process
os.makedirs(STAGE, exist_ok=True); os.makedirs(OUT, exist_ok=True)
errors = collections.Counter()

def log(*parts):
    line = time.strftime("%Y-%m-%dT%H:%M:%S ") + " ".join(str(x) for x in parts)
    print(line, flush=True)
    with open(f"{OUT}/run.log", "a") as f: f.write(line + "\n")

def http(method, url, body=None, timeout=60):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read())

def kb_count():
    try: return http("GET", f"{BASE}/kb/{KB}")["document_count"]
    except Exception as e: log("kb_count error", e); return -1

def _scroll_filenames(collection):
    names, offset = set(), None
    while True:
        body = {"limit": 10000, "with_payload": ["filename"], "with_vector": False}
        if offset is not None: body["offset"] = offset
        try:
            r = http("POST", f"{QD}/collections/{collection}/points/scroll", body, timeout=300)["result"]
        except Exception as e:
            log("scroll error", collection, e); return names
        for p in r["points"]:
            fn = p.get("payload", {}).get("filename")
            if fn: names.add(fn)
        offset = r.get("next_page_offset")
        if offset is None: return names

def ingested_filenames():
    """A file is handled when it has page vectors (the visual side is the
    last to be written), OR a visual job queued/in progress. Chunks alone
    are not enough on a visual KB: a document whose visual job failed
    terminally still has them. With --text-only (visual index off) chunks
    are the only evidence there is."""
    names = _scroll_filenames(KB) if args.text_only else _scroll_filenames(f"{KB}_pages")
    for st in ("queued", "in_progress"):
        try:
            for j in http("GET", f"{BASE}/ingest/jobs?status={st}", timeout=120)["jobs"]:
                if j.get("kb_name") == KB and j.get("filename"): names.add(j["filename"])
        except Exception as e:
            log("jobs error", st, e)
    return names

def wait_stable(minutes=2):
    log("gate: waiting for document_count to be stable", minutes, "min")
    last, same = kb_count(), 0
    while same < minutes:
        time.sleep(60); n = kb_count()
        same = same + 1 if n == last else 0; last = n
    log("gate: stable at", last)

def crawler_running():
    if not WAIT_PROC: return False
    return subprocess.run(["pgrep", "-f", WAIT_PROC], capture_output=True).returncode == 0

def all_pdfs():
    return {os.path.basename(p): p for p in glob.glob(os.path.join(ROOT, GLOB), recursive=True)}

wait_stable()
seq = 0
while True:
    pdfs = all_pdfs(); have = ingested_filenames()
    missing = sorted(n for n in pdfs if n not in have and errors[n] < SKIP_AFTER)
    skipped = sorted(n for n in pdfs if n not in have and errors[n] >= SKIP_AFTER)
    log(f"corpus {len(pdfs)} ingested {len(have)} missing {len(missing)} skipped(errors) {len(skipped)} crawler_running {crawler_running()}")
    if not missing:
        if crawler_running(): log("nothing missing; crawler still running, recheck in 10 min"); time.sleep(600); continue
        log("DONE. skipped:", skipped); break
    # Unique per batch. A counter that restarted at 1 after a runner restart
    # reused directories still holding the previous run's symlinks, so every
    # batch re-ingested 40 already-done documents (2026-09-18).
    chunks = [missing[i:i+BATCH] for i in range(0, min(len(missing), BATCH*CONCURRENT), BATCH)]
    def run_batch(names):
        global seq
        seq += 1
        d = f"{STAGE}/s{time.strftime('%Y%m%dT%H%M%S')}-{seq:04d}"
        if os.path.exists(d): raise SystemExit(f"staging dir exists: {d}")
        os.makedirs(d)
        for n in names:
            os.symlink(os.path.relpath(pdfs[n], d), f"{d}/{n}")
        t0 = time.time()
        body = {"directory": f"/host{d}", "kb_name": KB, "recursive": False, "extensions": ["pdf"],
                "enable_visual_index": True, "metadata": {**META, "batch": os.path.basename(d)}}
        try:
            resp = http("POST", f"{BASE}/ingest/directory", body, timeout=MAX_TIME)
        except Exception as e:
            log(f"{os.path.basename(d)} FAILED/cut after {int(time.time()-t0)}s: {e}"); return False
        json.dump(resp, open(f"{OUT}/{os.path.basename(d)}.json", "w"))
        st = collections.Counter(f["status"] for f in resp["files"])
        for f in resp["files"]:
            if f["status"] not in ("completed", "queued"):
                errors[f["filename"]] += 1
                with open(f"{OUT}/failures.jsonl", "a") as fh: fh.write(json.dumps(f) + "\n")
        log(f"{os.path.basename(d)} {int(time.time()-t0)}s found {resp['files_found']} {dict(st)} pages {sum(f.get('page_count') or 0 for f in resp['files'])}")
        return True
    with ThreadPoolExecutor(max_workers=CONCURRENT) as ex:
        results = list(ex.map(run_batch, chunks))
    if not all(results):
        wait_stable()
