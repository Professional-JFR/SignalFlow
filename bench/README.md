# SignalFlow Benchmark Guide

Local benchmarking baseline for the SignalFlow log-ingestion service.  
All steps run deterministically under 20 minutes on a developer laptop.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker + Compose | v2 | https://docs.docker.com/get-docker/ |
| Python 3 | ≥ 3.10 | stdlib only — no pip installs needed |
| k6 | ≥ 0.46 | https://k6.io/docs/get-started/installation/ |
| curl | any | usually pre-installed |
| bc | any | usually pre-installed |

---

## Run Order Checklist

Execute these steps in order. Estimated wall-clock time: **< 10 minutes**.

- [ ] **1. Start the stack**
  ```bash
  docker compose up --build -d
  ```
  Wait for the health check to pass:
  ```bash
  curl http://localhost:8080/actuator/health
  # → {"status":"UP","components":{"db":{"status":"UP"},...}}
  ```

- [ ] **2. Run the full benchmark suite (automated)**
  ```bash
  bash bench/run_bench.sh
  ```
  This script executes steps 3–6 automatically and prints a results summary.

  **Or run each step individually:**

- [ ] **3. Generate deterministic test data**
  ```bash
  python3 bench/gen_logs.py \
    --count 10000 \
    --seed 42 \
    --dup-ratio 0.3 \
    --out bench/data/logs.ndjson
  ```
  Produces 10 000 log events, 30 % duplicates, seeded for reproducibility.

- [ ] **4. Ingestion throughput benchmark**
  ```bash
  k6 run \
    -e BASE_URL=http://localhost:8080 \
    -e VUS=10 \
    -e DURATION=60s \
    bench/k6/ingest_test.js
  ```
  Record: **`http_req_duration p(95)`** and **`http_reqs/s`**.

- [ ] **5. Ranked incident query p95 latency benchmark**
  ```bash
  k6 run \
    -e BASE_URL=http://localhost:8080 \
    -e VUS=5 \
    -e DURATION=30s \
    bench/k6/query_test.js
  ```
  Record: **`http_req_duration p(95)`** for `GET /api/v1/groups/top`.

- [ ] **6. Dedup effectiveness from Prometheus**
  ```bash
  curl -s http://localhost:8080/actuator/prometheus \
    | grep -E 'signalflow_(dedup|ingest)_'
  ```
  Compute: `dedup_hits / dedup_total × 100%`.

- [ ] **7. Minute-bucket aggregation latency check**
  ```bash
  curl -s http://localhost:8080/actuator/prometheus \
    | grep signalflow_query_latency
  ```
  The `bucket_mode="unbucketed"` tag labels query latency against the
  pre-aggregated `fingerprint_minute_agg` table.

---

## Expected Output Examples

### Health endpoint
```json
{
  "status": "UP",
  "components": {
    "db":        { "status": "UP" },
    "livenessState":  { "status": "UP" },
    "readinessState": { "status": "UP" }
  }
}
```

### Prometheus metrics (excerpt)
```
signalflow_ingest_events_total 10000.0
signalflow_ingest_errors_total 0.0
signalflow_ingest_latency_seconds_count 10000.0
signalflow_ingest_latency_seconds_sum 4.231
signalflow_dedup_total_total 10000.0
signalflow_dedup_hits_total 2987.0
signalflow_query_latency_seconds_count{bucket_mode="unbucketed"} 450.0
signalflow_query_latency_seconds_max{bucket_mode="unbucketed"} 0.041
```

### k6 ingest summary (example)
```
── SignalFlow Ingest Benchmark ──────────────────────
  Total requests :  5820
  Duration (wall) : 60s
  Throughput      :  97.0 req/s
  p50 latency     :  42.1 ms
  p95 latency     : 134.7 ms
  p99 latency     : 210.3 ms
─────────────────────────────────────────────────────
```

### k6 query summary (example)
```
── SignalFlow Ranked Query Benchmark ────────────────
  Total requests :  288
  Duration (wall) : 30s
  Throughput      :  9.6 req/s
  p50 latency     :  18.4 ms
  p95 latency     :  37.2 ms
  p99 latency     :  58.6 ms
─────────────────────────────────────────────────────
```

---

## Quick-Fill Results Table

Copy this table into your notes after each benchmark run:

| Metric | Value | Notes |
|--------|-------|-------|
| Log ingestion throughput | _____ events/sec | k6 `http_reqs/s`, 10 VUs, 60 s |
| Ingestion p95 latency | _____ ms | k6 `http_req_duration p(95)` |
| Ranked query p95 latency | _____ ms | k6 `http_req_duration p(95)` |
| Dedup effectiveness | _____%  | `dedup_hits / dedup_total × 100` |
| Minute-bucket query p95 | _____ ms | Prometheus `signalflow_query_latency` |
| Dataset size | 10 000 events | seed=42, dup_ratio=0.30 |
| Stack | Spring Boot 3.3 + PostgreSQL 17 | local Docker |

---

## Which Numbers to Record for Resume Bullets

| Resume bullet placeholder | Where to find the number |
|---------------------------|--------------------------|
| "ingested **X** events/sec" | k6 `http_reqs/s` from ingest run |
| "p95 ingestion latency **X ms**" | k6 `http_req_duration p(95)` from ingest run |
| "ranked incident query p95 **X ms**" | k6 `http_req_duration p(95)` from query run |
| "SHA-256 fingerprinting deduped **X%** of events" | `dedup_hits / dedup_total × 100` from Prometheus |
| "minute-bucket aggregation delivered **X ms** p95 query latency" | Prometheus `signalflow_query_latency_seconds` histogram |

### Example bullet (fill in your actual numbers):
> "Built ingestion pipeline sustaining **~100 events/sec** at **< 150 ms p95** latency;  
> SHA-256 fingerprinting deduplicated **~30%** of events; minute-bucket aggregation  
> cut ranked incident query p95 to **< 40 ms** on a local PostgreSQL instance."

---

## Benchmark Notes

> **Machine / date / dataset caveat:**  
> Numbers above are illustrative examples only. Actual values depend on hardware,
> JVM warm-up state, Docker resource limits, and dataset size.  
> Record your machine spec (CPU cores, RAM, SSD/HDD) and the date alongside results.
> All runs use seed=42 for reproducibility — re-running with the same seed produces
> the same dataset and comparable numbers on the same machine.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `curl: health check failed` | Run `docker compose up --build` and wait ~10 s |
| `k6: command not found` | Install k6: https://k6.io/docs/get-started/installation/ |
| `bc: command not found` | Install bc: `apt install bc` / `brew install bc` |
| Prometheus metrics missing | Ensure `management.endpoints.web.exposure.include` includes `prometheus` in `application.yml` |
| High error rate in k6 | Reduce VUs or check service logs: `docker compose logs ingest` |
