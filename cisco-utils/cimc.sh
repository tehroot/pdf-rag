#!/bin/bash
# usage: cimc.sh <class> [<class> ...]  -> read-only configResolveClass via the CIMC XML API
# e.g.   cimc.sh computeRackUnit memoryUnit storageLocalDisk faultInst firmwareRunning
source "$(dirname "$0")/cimc_lib.sh"
[ $# -gt 0 ] || { sed -n '2,3p' "$0"; exit 2; }
cimc_login || exit 1
for cls in "$@"; do
  echo "### $cls"
  cimc_post "<configResolveClass cookie='$CIMC_COOKIE' inHierarchical='false' classId='$cls'/>"
  echo
done
