# SignalFlow

> **Log ingestion → deduplication → incident signals.**  
> A production-style polyglot pipeline that turns noisy logs into actionable alerts.

---

## What this vertical slice does (Phase 1)

This minimal end-to-end baseline implements the core ingestion loop:

```
HTTP POST /api/v1/logs
      │
      ▼
  Validate required fields
      │
      ▼
  Normalize message
  (lowercase, collapse whitespace, replace UUIDs → <UUID>, numbers → <NUM>)
      │
      ▼
  Compute fingerprint
  SHA-256( service | normalizedMessage | SEVERITY )
      │
      ▼
  Persist raw event  →  log_events
  Upsert minute bucket →  fingerprint_minute_agg
      │
      ▼
  GET /api/v1/groups/top?window=15m
  (sum event_count per fingerprint over window, return ranked list)
```

There is no ML in Phase 1 — regex normalization provides a deterministic,
explainable baseline that Phase 2 will improve with semantic embeddings.

---

## Repository layout

```
signalflow/
├── ingest-java/          # Spring Boot ingestion service
│   ├── src/main/java/…
│   ├── src/test/java/…
│   ├── pom.xml
│   └── Dockerfile
├── db/
│   └── migrations/       # Reference copies of SQL migrations
├── docker-compose.yml
└── README.md
```

---

## Cloud Deployment Evidence (Render)

**Deployment date:** July 11, 2026  
**Platform:** Render (Docker Web Service + Managed PostgreSQL)

### Live Service
- Base URL: https://signalflow-ingest.onrender.com
- Health endpoint: https://signalflow-ingest.onrender.com/actuator/health

### Verification Results
- Service status in Render: **Live**
- Application startup confirmed in logs (Spring Boot + embedded Tomcat)
- Database connectivity confirmed in health check:
  - `status: "UP"`
  - `components.db.status: "UP"`
- Flyway migration executed successfully on startup (`v1 - initial schema`)

### Notes
- Accessing `/` returns Spring Boot Whitelabel 404 because no root route is defined.
- Operational verification should use `/actuator/health` and other actuator endpoints.

### Deployment Architecture
- Java/Spring Boot ingestion API containerized with Docker
- Hosted on Render Web Service (Docker runtime)
- Backed by Render Managed PostgreSQL
- Environment-based runtime config (`PORT`, datasource env vars)

### What this demonstrates
- Cloud deployment of a production-style Java backend service
- Managed database integration and runtime configuration via environment variables
- Startup schema migrations with Flyway
- Health/readiness observability through Actuator endpoints
- Practical production troubleshooting (container pathing, env var mapping, DB host resolution)

---

## Quick start

### Prerequisites
- Docker + Docker Compose (v2)

### Run the full stack

```bash
docker compose up --build
```

The ingest service starts on `http://localhost:8080`.  
PostgreSQL is available on `localhost:5432` (user/pass/db all `signalflow`).

Flyway runs the schema migration automatically on startup.

---

## Example curl commands

### Ingest a log event

```bash
curl -s -X POST http://localhost:8080/api/v1/logs \
  -H "Content-Type: application/json" \
  -d '{
    "timestamp": "2026-05-29T12:00:00Z",
    "service":   "checkout",
    "env":       "prod",
    "severity":  "ERROR",
    "message":   "NullPointerException processing order 12345 for user 98",
    "traceId":   "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }' | jq .
```

Expected response:

```json
{
  "fingerprint": "3a7f...c9b1",
  "normalizedMessage": "nullpointerexception processing order <NUM> for user <NUM> traceid=<UUID>"
}
```

### Query top groups (last 15 minutes)

```bash
curl -s "http://localhost:8080/api/v1/groups/top?window=15m" | jq .
```

Supported windows: `15m`, `1h`, `24h`.

Expected response:

```json
{
  "window": "15m",
  "groups": [
    {
      "fingerprint":       "3a7f...c9b1",
      "service":           "checkout",
      "env":               "prod",
      "severity":          "ERROR",
      "normalizedMessage": "nullpointerexception processing order <NUM> for user <NUM>",
      "sampleMessage":     "NullPointerException processing order 12345 for user 98",
      "count":             42
    }
  ]
}
```

### Validation error example

```bash
curl -s -X POST http://localhost:8080/api/v1/logs \
  -H "Content-Type: application/json" \
  -d '{"service": "checkout"}' | jq .
```

```json
{
  "errors": {
    "timestamp": "timestamp is required",
    "env":       "env is required",
    "severity":  "severity is required",
    "message":   "message is required"
  }
}
```

### Health check

```bash
curl http://localhost:8080/actuator/health
```

---

## Running tests locally

Requires Java 17 and Maven (or just Docker if you use the build image).

```bash
cd ingest-java
mvn test
```

The integration test uses **Testcontainers** to spin up a real PostgreSQL instance,
so Docker must be running.

---

## DB schema

### `log_events`
Stores every accepted raw log entry.  
Indexed on `event_time`, `fingerprint`, and `(service, env, event_time)`.

### `fingerprint_minute_agg`
Pre-aggregated counts per fingerprint per minute bucket.  
Updated atomically via `INSERT … ON CONFLICT DO UPDATE`.  
Powers the `/groups/top` endpoint without scanning raw events.

See [`db/migrations/V1__initial_schema.sql`](db/migrations/V1__initial_schema.sql) for the full DDL.

---

## Architecture decisions

| Decision | Choice | Reason |
|---|---|---|
| DB | PostgreSQL 17 | Simpler than TimescaleDB for Phase 1; native partitioning is sufficient |
| Aggregation | Write-time upsert | Keeps query latency predictable without background jobs |
| Normalization | Regex (Phase 1) | Deterministic, explainable, measurable baseline for ML comparison |
| Fingerprint | SHA-256 of (service + normalizedMsg + severity) | Stable, fast, collision-resistant |
| Framework | Spring Boot 3 + JDBC | Mature HTTP + validation + Flyway support; SQL clarity over ORM magic |

---

## Resume-ready highlights

- Deployed a containerized **Spring Boot** ingestion service to **Render** using **Docker**, with environment-based configuration for production runtime.
- Provisioned and integrated **managed PostgreSQL** in the cloud; configured datasource environment variables and validated live DB connectivity.
- Implemented startup schema management with **Flyway migrations** and verified successful production migration execution.
- Published production health observability via **Actuator** (`/actuator/health`), confirming application and database readiness.
- Troubleshot and resolved deployment/runtime issues across container pathing, environment variable mapping, and database host resolution.

---

## Future phases

### Phase 2 — ML enhancements (`signalflow-ml`, Python + FastAPI)
- Sentence-embedding API for normalized messages
- Semantic grouping: merge fingerprints with cosine similarity > threshold
- Anomaly scoring (EWMA / z-score) to reduce alert noise
- `semantic_group_id` stored alongside each fingerprint

### Phase 3 — Evaluation + benchmarks
- Labeled dataset: hand-labeled "same issue / different issue" pairs
- Pairwise precision/recall/F1: fingerprint-only vs ML-assisted grouping
- Load generator (`tools/loadgen/`) measuring ingestion throughput and latency
- Results and methodology published in README

### Operational backlog
- Baseline spike alerting (`alerts` table + optional webhook)
- Backpressure: bounded in-memory queue + 429 when full
- Retention jobs: raw logs 7 days, aggregates 30 days
- Structured JSON logging + Prometheus metrics via Micrometer
- `POST /api/v1/logs/batch` for efficient bulk ingestion
<<<<<<< HEAD
=======
## Documentation

- [Deployment Runbook](docs/deployment.md)
- [Resume Project Notes](docs/resume.md)
- [Benchmark Guide](bench/README.md)

>>>>>>> 6d1cf07 (Add documentation index links to README)
