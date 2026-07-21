#!/usr/bin/env python3
"""
gen_logs.py — Deterministic log generator for SignalFlow benchmarks.

Usage:
    python3 gen_logs.py [options] > logs.ndjson

Options:
    --count N        Number of log events to generate (default: 10000)
    --seed N         Random seed for reproducibility (default: 42)
    --dup-ratio F    Fraction of events that are duplicates (0.0–1.0, default: 0.3)
    --services LIST  Comma-separated service names (default: checkout,payment,auth,order,notify)
    --out FILE       Write to FILE instead of stdout
    --help           Show this message

Output format: one JSON object per line (NDJSON), ready for POST /api/v1/logs.
"""

import argparse
import json
import random
import sys
from datetime import datetime, timezone, timedelta

SEVERITIES = ["ERROR", "WARN", "INFO"]

DEFAULT_SERVICES = ["checkout", "payment", "auth", "order", "notify"]

# Template messages — intentionally varied so normalization produces distinct fingerprints
MESSAGE_TEMPLATES = [
    "NullPointerException processing order {order_id} for user {user_id}",
    "Connection refused to database host {host} on port {port}",
    "Timeout after {ms}ms waiting for upstream service response",
    "Failed to parse JSON payload: unexpected token at position {pos}",
    "Transaction {tx_id} rolled back due to deadlock",
    "Cache miss for key user:{user_id} — falling back to database",
    "Rate limit exceeded for client {client_id}: {count} requests in {window}s",
    "Authentication failed for user {user_id}: invalid credentials",
    "Payment declined for order {order_id}: insufficient funds",
    "Retry {attempt} of {max_attempts} for downstream call to {service}",
    "Schema validation error: field {field} must be non-null",
    "Out of memory error in heap allocation for task {task_id}",
]


def render_template(tmpl: str, rng: random.Random) -> str:
    return tmpl.format(
        order_id=rng.randint(10000, 99999),
        user_id=rng.randint(100, 9999),
        host=f"db-{rng.randint(1, 5)}.internal",
        port=rng.choice([5432, 3306, 6379]),
        ms=rng.randint(100, 30000),
        pos=rng.randint(0, 500),
        tx_id=rng.randint(1000000, 9999999),
        client_id=f"client-{rng.randint(1, 50)}",
        count=rng.randint(10, 500),
        window=rng.choice([1, 5, 10, 60]),
        attempt=rng.randint(1, 3),
        max_attempts=3,
        service=rng.choice(DEFAULT_SERVICES),
        field=rng.choice(["userId", "orderId", "amount", "currency", "timestamp"]),
        task_id=rng.randint(1, 100),
    )


def generate(count: int, seed: int, dup_ratio: float, services: list[str]) -> list[dict]:
    rng = random.Random(seed)
    base_time = datetime(2026, 1, 1, 0, 0, 0, tzinfo=timezone.utc)

    # Pre-generate a pool of "unique" events that duplicates will copy from
    pool_size = max(1, int(count * (1 - dup_ratio)))
    pool = []
    for i in range(pool_size):
        svc = rng.choice(services)
        sev = rng.choices(SEVERITIES, weights=[50, 30, 20])[0]
        tmpl = rng.choice(MESSAGE_TEMPLATES)
        msg = render_template(tmpl, rng)
        pool.append({"service": svc, "severity": sev, "message": msg, "env": "bench"})

    events = []
    for i in range(count):
        ts = base_time + timedelta(seconds=i * 0.1)  # 10 events/sec spread

        if rng.random() < dup_ratio and pool:
            # Duplicate: copy service/severity/message from pool (simulate repeated error)
            base = rng.choice(pool)
            svc, sev, msg, env = base["service"], base["severity"], base["message"], base["env"]
        else:
            # Unique event
            entry = rng.choice(pool)
            svc, sev, env = entry["service"], entry["severity"], entry["env"]
            msg = render_template(rng.choice(MESSAGE_TEMPLATES), rng)

        events.append({
            "timestamp": ts.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "service": svc,
            "env": env,
            "severity": sev,
            "message": msg,
            "traceId": f"{rng.randint(0, 0xFFFFFFFF):08x}-{rng.randint(0, 0xFFFF):04x}-"
                       f"{rng.randint(0, 0xFFFF):04x}-{rng.randint(0, 0xFFFF):04x}-"
                       f"{rng.randint(0, 0xFFFFFFFFFFFF):012x}",
        })

    return events


def main():
    parser = argparse.ArgumentParser(
        description="Deterministic log generator for SignalFlow benchmarks.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--count", type=int, default=10000, help="Number of events")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--dup-ratio", type=float, default=0.3,
                        help="Duplicate fraction (0.0–1.0)")
    parser.add_argument("--services", default=",".join(DEFAULT_SERVICES),
                        help="Comma-separated service names")
    parser.add_argument("--out", default=None, help="Output file (default: stdout)")
    args = parser.parse_args()

    if not 0.0 <= args.dup_ratio <= 1.0:
        parser.error("--dup-ratio must be between 0.0 and 1.0")

    services = [s.strip() for s in args.services.split(",") if s.strip()]
    events = generate(args.count, args.seed, args.dup_ratio, services)

    out = open(args.out, "w") if args.out else sys.stdout
    try:
        for event in events:
            out.write(json.dumps(event) + "\n")
    finally:
        if args.out:
            out.close()

    print(f"# Generated {len(events)} events  seed={args.seed}  dup_ratio={args.dup_ratio}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
