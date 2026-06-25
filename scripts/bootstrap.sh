#!/usr/bin/env bash
# One-time dev setup: create .env, the embedding model, and the inbox dir so
# `docker compose up` has everything it needs.
#
# Usage: scripts/bootstrap.sh [--no-model] [--sidecar]
#   --no-model   skip downloading the llama embedding GGUF (~130 MB)
#   --sidecar    also create the Python sidecar venv (.venv) with dev deps
set -euo pipefail
. "$(dirname "$0")/lib.sh"

DOWNLOAD_MODEL=1
SETUP_SIDECAR=0
for arg in "$@"; do
  case "$arg" in
    --no-model) DOWNLOAD_MODEL=0 ;;
    --sidecar)  SETUP_SIDECAR=1 ;;
    -h|--help)  usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

# 1. .env from the example (never clobber an existing one).
if [ -f "$ROOT/.env" ]; then
  ok ".env already exists — left untouched"
else
  cp "$ROOT/.env.example" "$ROOT/.env"
  ok "created .env from .env.example (edit it to taste)"
fi
init_env

# 2. Inbox bind-mount dir (compose mounts $INGEST_INBOX or ./incoming at /docs).
INBOX="$(env_or INGEST_INBOX "$ROOT/incoming")"
mkdir -p "$INBOX"
ok "inbox ready: $INBOX  (drop files here, then ingest by path /docs/<file>)"

# 3. Embedding model GGUF for llama-server (bind-mounted from ./models).
MODEL_FILE="$(env_or LLAMA_MODEL_FILE bge-small-en-v1.5-f16.gguf)"
mkdir -p "$ROOT/models"
if [ "$DOWNLOAD_MODEL" = "0" ]; then
  warn "skipping model download (--no-model); llama-server needs models/$MODEL_FILE to start"
elif [ -f "$ROOT/models/$MODEL_FILE" ]; then
  ok "model present: models/$MODEL_FILE"
else
  require_cmd curl
  URL="https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/$MODEL_FILE"
  info "downloading $MODEL_FILE (~130 MB) from HuggingFace"
  if curl -fL --progress-bar -o "$ROOT/models/$MODEL_FILE.part" "$URL"; then
    mv "$ROOT/models/$MODEL_FILE.part" "$ROOT/models/$MODEL_FILE"
    ok "model downloaded: models/$MODEL_FILE"
  else
    rm -f "$ROOT/models/$MODEL_FILE.part"
    warn "download failed. Fetch it manually into ./models, e.g.:"
    warn "  huggingface-cli download CompendiumLabs/bge-small-en-v1.5-gguf $MODEL_FILE --local-dir ./models"
  fi
fi

# 4. Optional Python sidecar venv (only needed to run/test the sidecar locally;
#    the Docker image builds its own).
if [ "$SETUP_SIDECAR" = "1" ]; then
  require_cmd python3
  if [ -d "$ROOT/sidecar/.venv" ]; then
    ok "sidecar venv already exists"
  else
    info "creating sidecar venv with dev deps"
    python3 -m venv "$ROOT/sidecar/.venv"
    "$ROOT/sidecar/.venv/bin/pip" install -q --upgrade pip
    "$ROOT/sidecar/.venv/bin/pip" install -q -e "$ROOT/sidecar[dev]"
    ok "sidecar venv ready (.venv) — run tests with scripts/test.sh --sidecar"
  fi
fi

info "bootstrap complete. Next: scripts/up.sh   (add --gpu on an NVIDIA host)"
