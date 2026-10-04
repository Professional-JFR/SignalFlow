-- ============================================================
-- V1: Initial schema for SignalFlow Phase 1
-- ============================================================

-- ------------------------------------------------------------
-- log_events: raw accepted log entries
-- ------------------------------------------------------------
CREATE TABLE log_events (
    id                 BIGSERIAL    PRIMARY KEY,
    event_time         TIMESTAMPTZ  NOT NULL,
    ingest_time        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    service            TEXT         NOT NULL,
    env                TEXT         NOT NULL,
    severity           TEXT         NOT NULL,
    message            TEXT         NOT NULL,
    normalized_message TEXT         NOT NULL,
    fingerprint        CHAR(64)     NOT NULL,
    trace_id           TEXT,
    request_id         TEXT
);

-- Fast range scans over recent events
CREATE INDEX idx_log_events_event_time
    ON log_events (event_time DESC);

-- Per-fingerprint drill-down (sample events for a group)
CREATE INDEX idx_log_events_fingerprint_time
    ON log_events (fingerprint, event_time DESC);

-- Service + env time-range queries
CREATE INDEX idx_log_events_service_env_time
    ON log_events (service, env, event_time DESC);

-- ------------------------------------------------------------
-- fingerprint_minute_agg: pre-aggregated counts per minute bucket
-- Maintained via UPSERT on every ingestion; keeps top-groups
-- queries cheap without scanning raw events.
-- ------------------------------------------------------------
CREATE TABLE fingerprint_minute_agg (
    bucket_start       TIMESTAMPTZ  NOT NULL,
    service            TEXT         NOT NULL,
    env                TEXT         NOT NULL,
    fingerprint        CHAR(64)     NOT NULL,
    normalized_message TEXT         NOT NULL,
    severity           TEXT         NOT NULL,
    event_count        BIGINT       NOT NULL,
    first_seen         TIMESTAMPTZ  NOT NULL,
    last_seen          TIMESTAMPTZ  NOT NULL,
    sample_message     TEXT         NOT NULL,
    PRIMARY KEY (bucket_start, service, env, fingerprint)
);

-- Top-groups window queries (scan recent buckets, sum counts)
CREATE INDEX idx_fma_bucket_start
    ON fingerprint_minute_agg (bucket_start DESC);

-- Per-fingerprint trend queries
CREATE INDEX idx_fma_fingerprint_time
    ON fingerprint_minute_agg (fingerprint, bucket_start DESC);

-- Service + env time-range rollups
CREATE INDEX idx_fma_service_env_time
    ON fingerprint_minute_agg (service, env, bucket_start DESC);
