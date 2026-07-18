# Dev pipeline scripts

Shell wrappers around the Maven, `docker compose`, and `docker build` commands
for building and running the whole stack (qdrant + llama-server + colpali-server
+ pdf-rag-http). Portable bash — works on macOS (dev) and Linux (the R530).

Run any script with `-h` / `--help` for its options. All resolve the repo root
themselves, so they work from any directory. They read `.env` (via
[`lib.sh`](lib.sh)) but never clobber variables already set in your shell.

## Quickstart

```bash
scripts/bootstrap.sh      # .env + embedding model + ./incoming inbox (one time)
scripts/up.sh             # start the CPU stack in the background
scripts/status.sh         # containers + health probes
scripts/smoke.sh          # end-to-end wiring check
scripts/down.sh           # stop
```

On an NVIDIA host add `--gpu` to `up`/`build-images`/`pipeline` to use the CUDA
sidecar image and reserve the GPU (layers `docker-compose.gpu.yml`). The scripts
pass explicit `-f` flags, so they ignore the committed `docker-compose.override.yml`
symlink — `scripts/up.sh` is always CPU, `--gpu` is always GPU, regardless of it.

## Scripts

| Script | What it does |
|---|---|
| `bootstrap.sh` | One-time setup: copy `.env.example`→`.env`, download the embedding GGUF into `./models`, create the `./incoming` inbox. `--sidecar` also builds the Python venv; `--no-model` skips the download. |
| `build.sh` | Local Maven build of the Java jars. `--test` includes tests, `--http` builds only the HTTP transport + deps. (Not needed for images — the container builds its own jar.) |
| `build-images.sh` | `docker compose build` for the buildable services (colpali-server, pdf-rag-http). `--gpu` builds the CUDA sidecar, `--no-cache` rebuilds clean. |
| `up.sh` | Start the stack (`compose up -d`). `--gpu`, `--build`, or specific services. |
| `down.sh` | Stop the stack. `--volumes` also deletes named volumes (destroys data). |
| `restart.sh` | Restart one or all services. |
| `logs.sh` | Follow logs (all services, or one: `qdrant`/`llama-server`/`colpali-server`/`pdf-rag-http`). |
| `status.sh` | `compose ps` plus an HTTP health probe of each service. |
| `test.sh` | `--core` (default, fast) / `--full` (all Java modules) / `--sidecar` (pytest) / `--all`. |
| `smoke.sh` | Probe a running stack and exercise the pdf-rag-http REST surface end to end. |
| `clean.sh` | Tear down + delete volumes + `mvn clean`. `--all` also removes the sidecar venv. Keeps `./models`, `./incoming`, `.env`. |
| `pipeline.sh` | The capstone: test → build images → up → smoke. `--gpu`, `--full`, `--no-test`. |
| `size-corpus.sh` | Corpus-sizing scan: sample a directory of PDFs and project pages, chunks, embed time, and storage for both pipelines before ingesting. `--sample N`, `--json PATH`; run with no args for all options. Needs host Java 21 (or run `CorpusSizer` inside the pdf-rag-http container — see the script header). |
| `phrase-cluster.py` | Retrieval probe: embed a phrase, search a KB across all docs, and report hit concentration by document (boilerplate shows up as a few docs dominating). `--json PATH` adds 2D PCA coords of the hit embeddings for cluster visualization. Reads `QDRANT_URL`/`EMBED_BASE_URL`/`EMBED_MODEL` from env or `.env`; needs python3 + numpy. |
| `lib.sh` | Shared helpers (sourced, not run): compose wrapper + GPU overlay, `.env` loader, logging, health probes. |

## CPU vs GPU

The base `docker-compose.yml` is CPU-only (sidecar model `vidore/colsmolvlm-v0.1`,
`float32`). `docker-compose.gpu.yml` flips every sidecar default to GPU
(`Dockerfile.cuda` + `cuda`/`bfloat16` + `vidore/colqwen2-v1.0`) and adds the
NVIDIA device reservation, with `${VAR:-...}` fallbacks so anything you've pinned
in `.env` still wins. Two ways it gets layered:

- **`--gpu` flag** (`up`/`build-images`/`pipeline`) — the explicit, opt-in path.
- **committed `docker-compose.override.yml` symlink** → `docker-compose.gpu.yml`,
  which plain `docker compose up` auto-loads, making GPU the default *outside*
  the scripts. The scripts pass explicit `-f` and ignore it.

GPU needs `nvidia-container-toolkit` on the host. On a CPU host, prefer the
scripts (immune to the override) or `docker compose -f docker-compose.yml up`.

## Notes

- `llama-server` needs `models/<LLAMA_MODEL_FILE>` to exist before `up`;
  `bootstrap.sh` fetches it. `up.sh` warns if it's missing.
- Images: `qdrant` and `llama-server` are upstream (pulled); `colpali-server`
  and `pdf-rag-http` are built locally. The `pdf-rag-http` image runs its own
  multi-stage Maven build, so `build-images.sh` does **not** require local Maven.
- `.env` is gitignored; only `.env.example` is committed. `bootstrap.sh` creates
  `.env` from it and won't overwrite an existing one.
- `pdf-rag-http` sees two host dirs (read-only): the `./incoming` inbox at
  `/docs` (`INGEST_INBOX`) and `$HOME` at `/host` (`INGEST_HOST_ROOT`; set to
  `/` on Linux to expose the whole host FS to `POST /ingest/directory`).
