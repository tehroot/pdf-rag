#!/usr/bin/env bash
# Corpus-sizing scan: project pages, chunks, embed time, and storage for a
# directory of PDFs BEFORE committing it to the ingest pipeline.
#
# Usage: scripts/size-corpus.sh <directory> [CorpusSizer options]
#   e.g. scripts/size-corpus.sh /mnt/windisk/backup_files/milpdfs --sample 500 --json /tmp/sizing.json
#   (run with no args to see the full option list)
#
# Runs org.hayden.sizing.CorpusSizer from the built server-http quarkus-app
# (building it first if needed). Requires Java 21 on the host.
#
# On a deployed host without Java, run it inside the pdf-rag-http container
# instead (paths are container-side, e.g. under /host):
#   docker compose exec pdf-rag-http java \
#     -cp '/app/app/*:/app/lib/main/*:/app/lib/boot/*' \
#     org.hayden.sizing.CorpusSizer /host/<path> --sample 500
set -euo pipefail
. "$(dirname "$0")/lib.sh"

APP_DIR="$ROOT/server-http/target/quarkus-app"

if [ ! -d "$APP_DIR/lib/main" ]; then
  require_cmd mvn
  info "building server-http (first run)"
  ( cd "$ROOT" && run mvn -pl server-http -am package -DskipTests -q )
fi

require_cmd java
# lib/boot has jboss-logging, which PDFBox's commons-logging shim resolves to;
# the logmanager property quiets the JBoss LogManager startup warning.
exec java -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -cp "$APP_DIR/app/*:$APP_DIR/lib/main/*:$APP_DIR/lib/boot/*" \
  org.hayden.sizing.CorpusSizer "$@"
