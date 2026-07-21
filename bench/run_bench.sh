#!/usr/bin/env bash
# run_bench.sh — SignalFlow local benchmark orchestrator
#
# Usage:
#   bash bench/run_bench.sh [--base-url URL] [--count N] [--seed N] [--dup-ratio F]
#
# Prerequisites:
#   - docker compose up --build  (service running on :8080)
#   - python3 (stdlib only, no extra packages)
#   - k6 >= 0.46  (https://k6.io/docs/get-started/installation/)
#
# Estimated wall-clock time: <10 minutes

set -euo pipefail

# ── defaults ─────────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:8080}"
COUNT="${COUNT:-10000}"
SEED="${SEED:-42}"
DUP_RATIO="${DUP_RATIO:-0.3}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
DATA_FILE="${SCRIPT_DIR}/data/logs.ndjson"

# ── argument parsing ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)   BASE_URL="$2";   shift 2 ;;
    --count)      COUNT="$2";      shift 2 ;;
    --seed)       SEED="$2";       shift 2 ;;
    --dup-ratio)  DUP_RATIO="$2";  shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

mkdir -p "${RESULTS_DIR}"
mkdir -p "$(dirname "${DATA_FILE}")"

echo "╔══════════════════════════════════════════════════════╗"
echo "║   SignalFlow Local Benchmark Suite                   ║"
echo "╚══════════════════════════════════════════════════════╝"
echo "  BASE_URL  : ${BASE_URL}"
echo "  COUNT     : ${COUNT}"
echo "  SEED      : ${SEED}"
echo "  DUP_RATIO : ${DUP_RATIO}"
echo ""

# ── Step 0: wait for service to be ready ─────────────────────────────────────
echo "▶ [0/5] Waiting for service health check…"
for i in $(seq 1 30); do
  if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
    echo "  ✓ Service is up"
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "  ✗ Service did not start in 30s. Run: docker compose up --build"
    exit 1
  fi
  sleep 1
done
echo ""

# ── Step 1: generate deterministic log dataset ────────────────────────────────
echo "▶ [1/5] Generating ${COUNT} log events (seed=${SEED}, dup_ratio=${DUP_RATIO})…"
python3 "${SCRIPT_DIR}/gen_logs.py" \
  --count "${COUNT}" \
  --seed  "${SEED}" \
  --dup-ratio "${DUP_RATIO}" \
  --out "${DATA_FILE}"
echo "  ✓ Dataset written to ${DATA_FILE}"
echo ""

# ── Step 2: warm up (sequential load to seed dedup groups) ───────────────────
echo "▶ [2/5] Warm-up — loading ${COUNT} events sequentially…"
WARM_START=$(date +%s)
SUCCESS=0
FAIL=0
while IFS= read -r line; do
  HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
    -X POST "${BASE_URL}/api/v1/logs" \
    -H "Content-Type: application/json" \
    -d "${line}" || echo "000")
  if [[ "${HTTP_CODE}" == "200" ]]; then
    ((SUCCESS++)) || true
  else
    ((FAIL++)) || true
  fi
done < "${DATA_FILE}"
WARM_END=$(date +%s)
WARM_DUR=$((WARM_END - WARM_START))
echo "  ✓ Warm-up done in ${WARM_DUR}s  (success=${SUCCESS}  fail=${FAIL})"
echo ""

# ── Step 3: ingestion throughput benchmark (k6) ───────────────────────────────
echo "▶ [3/5] Ingestion throughput benchmark (k6 — 10 VUs, 60 s)…"
k6 run \
  --env BASE_URL="${BASE_URL}" \
  --env VUS=10 \
  --env DURATION=60s \
  "${SCRIPT_DIR}/k6/ingest_test.js" \
  2>&1 | tee "${RESULTS_DIR}/ingest-run.log"
echo ""

# ── Step 4: ranked incident query p95 benchmark (k6) ─────────────────────────
echo "▶ [4/5] Ranked incident query p95 benchmark (k6 — 5 VUs, 30 s)…"
k6 run \
  --env BASE_URL="${BASE_URL}" \
  --env VUS=5 \
  --env DURATION=30s \
  "${SCRIPT_DIR}/k6/query_test.js" \
  2>&1 | tee "${RESULTS_DIR}/query-run.log"
echo ""

# ── Step 5: dedup effectiveness from Prometheus metrics ──────────────────────
echo "▶ [5/5] Collecting dedup effectiveness metrics from Prometheus endpoint…"
PROM=$(curl -sf "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "")

DEDUP_TOTAL=$(echo "${PROM}" | grep -E '^signalflow_dedup_total_total ' | awk '{print $2}' || echo "N/A")
DEDUP_HITS=$(echo "${PROM}" | grep -E '^signalflow_dedup_hits_total '  | awk '{print $2}' || echo "N/A")

echo ""
echo "══════════════════════════════════════════════════════"
echo "  RESULTS SUMMARY"
echo "══════════════════════════════════════════════════════"
echo "  Warm-up throughput  : $(echo "scale=1; ${COUNT} / ${WARM_DUR}" | bc) events/sec (sequential)"
echo "  Dedup total         : ${DEDUP_TOTAL}"
echo "  Dedup hits          : ${DEDUP_HITS}"
if [[ "${DEDUP_TOTAL}" != "N/A" && "${DEDUP_TOTAL}" != "0" ]]; then
  EFFECTIVENESS=$(echo "scale=1; ${DEDUP_HITS} * 100 / ${DEDUP_TOTAL}" | bc)
  echo "  Dedup effectiveness : ${EFFECTIVENESS}%"
fi
echo ""
echo "  See bench/results/ for full k6 JSON output."
echo "  See bench/README.md for how to record these numbers as resume bullets."
echo "══════════════════════════════════════════════════════"
