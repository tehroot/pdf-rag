#!/usr/bin/env python3
"""Phrase-clustering probe: embed a phrase, search a KB's chunk collection
across all documents, and report how the hits cluster.

Prints a concentration summary (which documents dominate the hits, how the
score decays by rank) and, with --json, writes the full hit list with 2D PCA
coordinates of the hit embeddings — position ~ semantic similarity, so tight
multi-document clusters are near-identical text (boilerplate), spread within
one document is varied usage. Useful for spotting boilerplate pollution
(e.g. "distribution statement" pages) before it degrades fusion ranking.

Examples:
  scripts/phrase-cluster.py milpdfs "distribution statement"
  scripts/phrase-cluster.py milpdfs "environmental test requirements" \
      --limit 200 --json /tmp/hits.json
  QDRANT_URL=http://r530:6333 EMBED_BASE_URL=http://r530:8081/v1 \
      scripts/phrase-cluster.py analog-datasheets "switching regulator"

Config comes from flags, else the environment, else .env at the repo root
(never clobbering shell vars, same as lib.sh), else the project defaults.
Requires numpy (PCA only); everything else is stdlib.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def load_env():
    """Export KEY=VALUE lines from .env without clobbering the shell env,
    mirroring lib.sh: skip comments, strip a whitespace-preceded inline '#'."""
    f = REPO_ROOT / ".env"
    if not f.is_file():
        return
    for line in f.read_text().splitlines():
        line = line.strip("\n")
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        if not key or not key[0].isalpha() and key[0] != "_":
            continue
        if key in os.environ:
            continue
        for i, ch in enumerate(val):
            if ch == "#" and i > 0 and val[i - 1] in " \t":
                val = val[:i]
                break
        os.environ[key] = val.rstrip()


def env(key, default):
    """Env lookup treating blank/whitespace values as unset (docker-compose
    defaults the api-key vars to a single space to appease SmallRye)."""
    v = os.environ.get(key, "").strip()
    return v if v else default


def post(url, body, headers):
    req = urllib.request.Request(
        url, json.dumps(body).encode(),
        {"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"error: {url} -> HTTP {e.code}: {e.read().decode(errors='replace')[:500]}")
    except urllib.error.URLError as e:
        sys.exit(f"error: cannot reach {url}: {e.reason}")


def pca_2d(vectors):
    """Project row vectors to 2D via SVD; deterministic sign convention.
    Returns (coords, explained_variance_ratio_of_the_two_components)."""
    import numpy as np
    x = np.asarray(vectors, dtype=np.float64)
    centered = x - x.mean(axis=0)
    _, s, vt = np.linalg.svd(centered, full_matrices=False)
    comps = vt[:2].copy()
    for i in range(2):
        if comps[i][np.argmax(np.abs(comps[i]))] < 0:
            comps[i] = -comps[i]
    evr = float((s[:2] ** 2).sum() / (s ** 2).sum())
    return centered @ comps.T, evr


def main():
    load_env()
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("kb", help="knowledge base (Qdrant chunk collection name)")
    ap.add_argument("phrase", help="phrase to embed and search for")
    ap.add_argument("--limit", type=int, default=400, help="hits to retrieve (default 400)")
    ap.add_argument("--top", type=int, default=12, help="docs to list in the summary (default 12)")
    ap.add_argument("--json", metavar="PATH", help="write hits + PCA coords as JSON")
    ap.add_argument("--qdrant-url", default=env("QDRANT_URL", "http://localhost:6333"))
    ap.add_argument("--embed-url", default=env("EMBED_BASE_URL", "http://localhost:8081/v1"),
                    help="OpenAI-compatible embeddings root (…/v1)")
    ap.add_argument("--embed-model", default=env("EMBED_MODEL", "bge-small-en-v1.5"))
    a = ap.parse_args()

    embed_headers = {}
    if env("EMBED_API_KEY", None):
        embed_headers["Authorization"] = "Bearer " + env("EMBED_API_KEY", "")
    qdrant_headers = {}
    if env("QDRANT_API_KEY", None):
        qdrant_headers["api-key"] = env("QDRANT_API_KEY", "")

    emb = post(a.embed_url.rstrip("/") + "/embeddings",
               {"model": a.embed_model, "input": [a.phrase]}, embed_headers)
    qvec = emb["data"][0]["embedding"]

    hits = post(f"{a.qdrant_url.rstrip('/')}/collections/{a.kb}/points/search", {
        "vector": qvec,
        "limit": a.limit,
        "with_payload": ["filename", "page_start", "chunk_index", "text"],
        "with_vector": bool(a.json),
    }, qdrant_headers)["result"]
    if not hits:
        sys.exit(f"no hits in '{a.kb}' (empty or missing collection?)")

    docs = {}
    for h in hits:
        fn = h["payload"].get("filename", "?")
        docs[fn] = docs.get(fn, 0) + 1
    top50_docs = len({h["payload"].get("filename", "?") for h in hits[:50]})

    print(f"phrase          {a.phrase!r}  ({len(qvec)}-dim {a.embed_model})")
    print(f"kb              {a.kb} @ {a.qdrant_url}")
    print(f"hits            {len(hits)}  scores {hits[0]['score']:.4f} .. {hits[-1]['score']:.4f}")
    print(f"distinct docs   {len(docs)}  ({top50_docs} in the top 50 hits)")
    print(f"top {min(a.top, len(docs))} docs by hit count:")
    for fn, n in sorted(docs.items(), key=lambda kv: -kv[1])[:a.top]:
        print(f"  {n:5d}  {fn}")

    if a.json:
        coords, evr = pca_2d([h["vector"] for h in hits])
        points = []
        for h, (x, y) in zip(hits, coords):
            p = h["payload"]
            points.append({
                "x": round(float(x), 4), "y": round(float(y), 4),
                "score": round(h["score"], 4),
                "filename": p.get("filename", "?"),
                "page": p.get("page_start"), "chunk": p.get("chunk_index"),
                "snippet": (p.get("text") or "").strip().replace("\n", " ")[:220],
            })
        with open(a.json, "w") as f:
            json.dump({"phrase": a.phrase, "kb": a.kb, "limit": a.limit,
                       "embed_model": a.embed_model,
                       "distinct_docs": len(docs),
                       "pca_explained_variance": round(evr, 4),
                       "points": points}, f, indent=1)
        print(f"json written: {a.json}  (PCA explains {evr:.0%} of hit-set variance)")


if __name__ == "__main__":
    main()
