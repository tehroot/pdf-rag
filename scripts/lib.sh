#!/usr/bin/env bash
# Shared helpers for the pdf-rag-ingest dev-pipeline scripts.
# Sourced by every other script in this directory; not meant to be run directly.
#
# Portable bash (works on macOS's bash 3.2 and Linux). No bash-4 features
# (associative arrays, ${var,,}, mapfile).

# Repo root = parent of this scripts/ dir, resolved regardless of CWD.
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"

# ---- logging ---------------------------------------------------------------
if [ -t 1 ]; then
  _C_RESET="$(printf '\033[0m')"; _C_BLUE="$(printf '\033[34m')"
  _C_GREEN="$(printf '\033[32m')"; _C_YELLOW="$(printf '\033[33m')"
  _C_RED="$(printf '\033[31m')"; _C_DIM="$(printf '\033[2m')"
else
  _C_RESET=""; _C_BLUE=""; _C_GREEN=""; _C_YELLOW=""; _C_RED=""; _C_DIM=""
fi
info() { printf '%s==>%s %s\n' "$_C_BLUE" "$_C_RESET" "$*"; }
ok()   { printf '%s ok%s %s\n' "$_C_GREEN" "$_C_RESET" "$*"; }
warn() { printf '%swarn%s %s\n' "$_C_YELLOW" "$_C_RESET" "$*" >&2; }
err()  { printf '%serr%s %s\n' "$_C_RED" "$_C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }
run()  { printf '%s$ %s%s\n' "$_C_DIM" "$*" "$_C_RESET"; "$@"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

# Print the calling script's leading comment block (the header docs), stripping
# the "# " prefix. Used for -h/--help. $0 is the running script, not lib.sh.
usage() {
  awk 'NR==1 { next }                       # skip shebang
       /^#/  { sub(/^# ?/, ""); print; next } # comment lines = the help text
       { exit }' "$0"
}

confirm() {
  # confirm "prompt" -> returns 0 if user types y/Y. Auto-yes when ASSUME_YES=1.
  [ "${ASSUME_YES:-0}" = "1" ] && return 0
  printf '%s [y/N] ' "$1"
  read -r _reply
  case "$_reply" in [yY]*) return 0 ;; *) return 1 ;; esac
}

# ---- .env loading ----------------------------------------------------------
# Export KEY=VALUE lines from .env, but never clobber values already in the
# environment (mirrors how docker compose treats the shell vs .env).
load_env() {
  local f="$ROOT/.env" line key val
  [ -f "$f" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"
    case "$key" in [A-Za-z_]*) ;; *) continue ;; esac
    printenv "$key" >/dev/null 2>&1 && continue
    val="${line#*=}"
    # Strip a whitespace-preceded inline comment (matches docker compose's
    # dotenv parser): a '#' that follows a space/tab begins a comment; a '#'
    # with no leading whitespace (e.g. inside a token) is kept. %% strips from
    # the FIRST such marker. Then trim any trailing whitespace it left behind.
    val="${val%%[[:space:]]#*}"
    val="${val%"${val##*[![:space:]]}"}"
    export "$key=$val"
  done < "$f"
}

# Echo $1 if set in the environment, else $2.
env_or() { eval "printf '%s' \"\${$1:-$2}\""; }

# ---- docker compose wrapper ------------------------------------------------
# Detect compose v2 ("docker compose") vs legacy v1 ("docker-compose").
if docker compose version >/dev/null 2>&1; then
  DC_BIN=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC_BIN=(docker-compose)
else
  DC_BIN=()
fi

# Flip the stack to GPU: layer docker-compose.gpu.yml AND switch the sidecar to
# the CUDA image + cuda runtime. Only fills in defaults the user hasn't already
# set (in their shell or .env), so an existing GPU .env is respected.
enable_gpu() {
  GPU=1
  : "${COLPALI_DOCKERFILE:=Dockerfile.cuda}"
  : "${COLPALI_DEVICE:=cuda}"
  : "${COLPALI_DTYPE:=bfloat16}"
  : "${COLPALI_MODEL:=vidore/colqwen2-v1.0}"
  export GPU COLPALI_DOCKERFILE COLPALI_DEVICE COLPALI_DTYPE COLPALI_MODEL
}

# Run docker compose with the base file (+ GPU overlay when GPU=1), from $ROOT.
dc() {
  [ "${#DC_BIN[@]}" -gt 0 ] || die "docker compose not found (install Docker Compose v2)"
  local files=(-f "$ROOT/docker-compose.yml")
  if [ "${GPU:-0}" = "1" ]; then
    files+=(-f "$ROOT/docker-compose.gpu.yml")
  fi
  ( cd "$ROOT" && run "${DC_BIN[@]}" "${files[@]}" "$@" )
}

# ---- http helpers (for smoke / health) -------------------------------------
# HTTP status code for a URL. curl's -w already prints 000 on connection
# failure; `|| true` just swallows curl's non-zero exit (no extra output).
http_code() { curl -s -o /dev/null -m "${2:-5}" -w '%{http_code}' "$1" 2>/dev/null || true; }

# True if the URL answers with ANY http response (server is up, route may 4xx/5xx).
http_alive() { [ "$(http_code "$1" "${2:-5}")" != "000" ]; }

# Load .env and resolve the service ports (env/.env override; defaults match
# .env.example). Call this near the top of any script that needs ports.
init_env() {
  load_env
  PDF_RAG_PORT="$(env_or PDF_RAG_PORT 8080)"
  QDRANT_PORT="$(env_or QDRANT_PORT 6333)"
  LLAMA_PORT="$(env_or LLAMA_PORT 8081)"
  COLPALI_PORT="$(env_or COLPALI_PORT 8090)"
}
