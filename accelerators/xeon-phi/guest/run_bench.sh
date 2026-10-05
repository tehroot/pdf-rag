#!/bin/bash
# Build phi_bench, run every case on the card, and guard the temperature:
# if the die reaches LIMIT_C, kill the benchmark and stop.
LIMIT_C=90
. /opt/mpss/3.8.6/environment-setup-k1om-mpss-linux
cd /root/k1om-probe
k1om-mpss-linux-gcc $CFLAGS -O2 -Wa,-march=k1om phi_bench.c -o phi_bench -lpthread || exit 1
scp -q phi_bench mic0:/tmp/phi_bench || exit 1

temp() { micsmc -t 2>/dev/null | awk '/Cpu Temp/{print int($4); exit}'; }
power() { micsmc -f 2>/dev/null | awk '/Total Power/{print int($4); exit}'; }

MAXT=$(temp)
( while :; do
    t=$(temp); [ -n "$t" ] && echo "$t" >> /tmp/bench_temps
    if [ -n "$t" ] && [ "$t" -ge "$LIMIT_C" ]; then
      echo "THERMAL LIMIT $t C: killing the benchmark" >&2
      ssh mic0 'killall phi_bench' ; touch /tmp/bench_abort
    fi
    sleep 1
  done ) & GUARD=$!
rm -f /tmp/bench_temps /tmp/bench_abort

echo "start: die $(temp) C, power $(power) W"
run() {
  [ -f /tmp/bench_abort ] && return
  ssh mic0 "/tmp/phi_bench $*"
}
run -t fma -p 1 -n 40000000
run -t fma -p 2 -n 40000000
run -t fma -p 3 -n 30000000
run -t fma -p 4 -n 20000000
run -t kernl1 -p 2 -n 40000000
run -t kernl1 -p 4 -n 20000000
run -t stream -p 1 -r 10
run -t stream -p 2 -r 10
run -t stream -p 4 -r 10
run -t stream -p 4 -r 10 -nopf
run -t stream8 -p 4 -r 10
run -t kernstream -p 4 -r 2
run -t kernstream -p 4 -r 2 -nopf
echo "end: die $(temp) C, power $(power) W; highest die temperature during the runs: $(sort -n /tmp/bench_temps | tail -1) C"
kill $GUARD 2>/dev/null
