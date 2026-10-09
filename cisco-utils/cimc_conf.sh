#!/bin/bash
# usage: cimc_conf.sh '<xml call>'  -> send one arbitrary XML API call; the literal word COOKIE
#        is replaced with the session cookie. This can CHANGE the server: check the XML first.
# e.g.   cimc_conf.sh "<configResolveChildren cookie='COOKIE' inDn='sys/svc-ext/vmedia-svc' inHierarchical='false'/>"
source "$(dirname "$0")/cimc_lib.sh"
[ $# -eq 1 ] || { sed -n '2,4p' "$0"; exit 2; }
cimc_login || exit 1
cimc_post "${1//COOKIE/$CIMC_COOKIE}"
echo
