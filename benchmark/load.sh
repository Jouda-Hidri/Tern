#!/usr/bin/env bash
set -euo pipefail

# Compares the concurrency model under load. Run it once on the coroutine build and once on
# the virtual-thread build; the numbers only mean something side by side.
#
#   ./benchmark/load.sh coroutines
#   ./benchmark/load.sh virtual-threads
#
# Containers are pinned to 1 CPU because that is what deployment/*.yaml sets, and it is the
# only setting where the difference can show: Dispatchers.Default is sized to the core count
# with a floor of 2, so an unconstrained laptop hides the effect entirely.

LABEL="${1:-run}"
HOST="${HOST:-http://localhost:8080}"
REQUESTS="${REQUESTS:-600}"
CONCURRENCY="${CONCURRENCY:-60}"
OUT="${OUT:-/tmp/bench-$LABEL}"

mkdir -p "$OUT"
printf '{"text":"Bonjour tout le monde, ceci est une mesure de charge"}' > "$OUT/body.json"

echo "== $LABEL =="
docker update --cpus=1 tern-artic-1 tern-antarctic-1 >/dev/null
echo "   containers pinned to 1 cpu"

until [ "$(curl -s -o /dev/null -w '%{http_code}' "$HOST/actuator/health/readiness")" = "200" ]; do sleep 1; done
for _ in $(seq 1 40); do curl -s -o /dev/null -X POST "$HOST/" -H 'Content-Type: application/json' -d @"$OUT/body.json"; done
echo "   warmed up"

run() {
  local name=$1; shift
  ab -q -n "$REQUESTS" -c "$CONCURRENCY" "$@" > "$OUT/$name.txt" 2>&1 || true
  local rps p50 p95 p99 failed
  rps=$(grep -E "^Requests per second" "$OUT/$name.txt" | awk '{print $4}')
  p50=$(awk '/^  50%/ {print $2}' "$OUT/$name.txt")
  p95=$(awk '/^  95%/ {print $2}' "$OUT/$name.txt")
  p99=$(awk '/^  99%/ {print $2}' "$OUT/$name.txt")
  failed=$(grep -E "^Failed requests" "$OUT/$name.txt" | awk '{print $3}')
  printf '   %-6s rps=%-8s p50=%-5s p95=%-6s p99=%-6s failed=%s\n' "$name" "$rps" "$p50" "$p95" "$p99" "$failed"
}

run POST -p "$OUT/body.json" -T application/json "$HOST/"
run GET "$HOST/"

docker update --cpus=0 tern-artic-1 tern-antarctic-1 >/dev/null 2>&1 || true
