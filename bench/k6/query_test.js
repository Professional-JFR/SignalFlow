/**
 * k6 ranked incident query benchmark — GET /api/v1/groups/top
 *
 * Run:
 *   k6 run bench/k6/query_test.js
 *
 * Or with options:
 *   k6 run -e BASE_URL=http://localhost:8080 \
 *           -e VUS=5 -e DURATION=30s \
 *           bench/k6/query_test.js
 *
 * Outputs:
 *   - http_req_duration p95 → "ranked incident query p95 latency"
 *   - http_reqs / duration  → queries/sec
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const VUS      = parseInt(__ENV.VUS      || "5");
const DURATION = __ENV.DURATION  || "30s";

// Windows to cycle through so we exercise all aggregation paths
const WINDOWS = ["15m", "1h", "24h"];

export const options = {
  vus:      VUS,
  duration: DURATION,
  thresholds: {
    http_req_duration: ["p(95)<500"],   // 500 ms soft threshold
    http_req_failed:   ["rate<0.01"],
  },
};

const queryDur = new Trend("signalflow_query_duration_ms", true);

export default function () {
  const window  = WINDOWS[__ITER % WINDOWS.length];
  const url     = `${BASE_URL}/api/v1/groups/top?window=${window}`;
  const headers = { "X-Correlation-Id": `bench-query-vu${__VU}-iter${__ITER}` };

  const res = http.get(url, { headers });

  const ok = check(res, {
    "status 200":   (r) => r.status === 200,
    "has window":   (r) => { try { return JSON.parse(r.body).window === window; } catch { return false; } },
    "has groups":   (r) => { try { return Array.isArray(JSON.parse(r.body).groups); } catch { return false; } },
  });

  queryDur.add(res.timings.duration);

  sleep(0.05); // 50 ms think time
}

export function handleSummary(data) {
  const ts    = new Date().toISOString().replace(/[:.]/g, "-");
  const fname = `bench/results/query-${ts}.json`;
  return {
    [fname]: JSON.stringify(data, null, 2),
    stdout:  textSummary(data),
  };
}

function textSummary(data) {
  const m   = data.metrics;
  const dur = m["http_req_duration"];
  const rps = m["http_reqs"];
  return [
    "── SignalFlow Ranked Query Benchmark ────────────────",
    `  Total requests : ${rps ? rps.values.count : "n/a"}`,
    `  Duration (wall) : ${data.state.testRunDurationMs / 1000}s`,
    `  Throughput      : ${rps ? (rps.values.count / (data.state.testRunDurationMs / 1000)).toFixed(1) : "n/a"} req/s`,
    `  p50 latency     : ${dur ? dur.values["p(50)"].toFixed(1) : "n/a"} ms`,
    `  p95 latency     : ${dur ? dur.values["p(95)"].toFixed(1) : "n/a"} ms`,
    `  p99 latency     : ${dur ? dur.values["p(99)"].toFixed(1) : "n/a"} ms`,
    "─────────────────────────────────────────────────────",
  ].join("\n");
}
