#!/usr/bin/env bash
# Maven build of the Java modules (local jars). Separate from the container
# build — the pdf-rag-http image builds its own jar in a multi-stage Dockerfile,
# so you only need this for fast local iteration or to produce runnable jars.
#
# Usage: scripts/build.sh [--test] [--http]
#   (default)  full reactor, skip tests  (mvn -B package -DskipTests)
#   --test     include tests             (mvn -B package)
#   --http     only server-http + deps   (mvn -B -pl server-http -am package -DskipTests)
set -euo pipefail
. "$(dirname "$0")/lib.sh"

require_cmd mvn
ARGS=(-B package -DskipTests)
for arg in "$@"; do
  case "$arg" in
    --test) ARGS=(-B package) ;;
    --http) ARGS=(-B -pl server-http -am package -DskipTests) ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

info "mvn ${ARGS[*]}"
( cd "$ROOT" && run mvn "${ARGS[@]}" )
ok "build complete — runnable jar: server-http/target/quarkus-app/quarkus-run.jar"
