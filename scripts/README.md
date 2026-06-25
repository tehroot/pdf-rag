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
sidecar image and reserve the GPU (layers `docker-compose.gpu.yml`).

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
| `lib.sh` | Shared helpers (sourced, not run): compose wrapper + GPU overlay, `.env` loader, logging, health probes. |

## CPU vs GPU

The base `docker-compose.yml` is CPU-only (sidecar model `vidore/colsmolvlm-v0.1`,
`float32`). The `--gpu` flag layers `docker-compose.gpu.yml` (NVIDIA device
reservation) **and** switches the sidecar to `Dockerfile.cuda` + `cuda`/`bfloat16`
+ `vidore/colqwen2-v1.0` — unless you've already set those in `.env`, which is
respected. GPU needs `nvidia-container-toolkit` on the host.

## Notes

- `llama-server` needs `models/<LLAMA_MODEL_FILE>` to exist before `up`;
  `bootstrap.sh` fetches it. `up.sh` warns if it's missing.
- Images: `qdrant` and `llama-server` are upstream (pulled); `colpali-server`
  and `pdf-rag-http` are built locally. The `pdf-rag-http` image runs its own
  multi-stage Maven build, so `build-images.sh` does **not** require local Maven.
- `.env` is gitignored; only `.env.example` is committed. `bootstrap.sh` creates
  `.env` from it and won't overwrite an existing one.
