package dev.signalflow.ingest.repository;

import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.dto.TopGroupsResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Repository
public class LogEventRepository {

    private final JdbcTemplate jdbc;

    public LogEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns {@code true} when at least one row with the given fingerprint
     * already exists in the minute-aggregate table (used for dedup hit tracking).
     */
    public boolean fingerprintExists(String fingerprint) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fingerprint_minute_agg WHERE fingerprint = ?)",
                Boolean.class,
                fingerprint);
        return Boolean.TRUE.equals(exists);
    }

    public void saveLogEvent(LogRequest request, String normalizedMessage, String fingerprint) {
        jdbc.update("""
                INSERT INTO log_events
                    (event_time, service, env, severity, message,
                     normalized_message, fingerprint, trace_id, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(request.timestamp()),
                request.service(),
                request.env(),
                request.severity().toUpperCase(),
                request.message(),
                normalizedMessage,
                fingerprint,
                request.traceId(),
                request.requestId());
    }

    /**
     * Upserts the per-minute aggregate bucket for the given event.
     * On conflict the count is incremented and last_seen is advanced;
     * the original sample_message is preserved so the group always has
     * a stable representative example.
     */
    public void upsertMinuteAggregate(LogRequest request, String normalizedMessage, String fingerprint) {
        Instant bucket = request.timestamp().truncatedTo(ChronoUnit.MINUTES);
        Timestamp ts = Timestamp.from(request.timestamp());
        jdbc.update("""
                INSERT INTO fingerprint_minute_agg
                    (bucket_start, service, env, fingerprint, normalized_message,
                     severity, event_count, first_seen, last_seen, sample_message)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (bucket_start, service, env, fingerprint)
                DO UPDATE SET
                    event_count   = fingerprint_minute_agg.event_count + 1,
                    last_seen     = GREATEST(fingerprint_minute_agg.last_seen, EXCLUDED.last_seen),
                    sample_message = fingerprint_minute_agg.sample_message
                """,
                Timestamp.from(bucket),
                request.service(),
                request.env(),
                fingerprint,
                normalizedMessage,
                request.severity().toUpperCase(),
                ts,
                ts,
                request.message());
    }

    /**
     * Returns up to 20 fingerprint groups with the highest event counts since {@code since}.
     * Results are summed across all minute buckets in the window.
     */
    public List<TopGroupsResponse.GroupEntry> topGroups(Instant since) {
        return jdbc.query("""
                SELECT
                    fingerprint,
                    MIN(service)          AS service,
                    MIN(env)              AS env,
                    MIN(severity)         AS severity,
                    MIN(normalized_message) AS normalized_message,
                    MIN(sample_message)   AS sample_message,
                    SUM(event_count)      AS total_count
                FROM fingerprint_minute_agg
                WHERE bucket_start >= ?
                GROUP BY fingerprint
                ORDER BY total_count DESC
                LIMIT 20
                """,
                (rs, rowNum) -> new TopGroupsResponse.GroupEntry(
                        rs.getString("fingerprint"),
                        rs.getString("service"),
                        rs.getString("env"),
                        rs.getString("severity"),
                        rs.getString("normalized_message"),
                        rs.getString("sample_message"),
                        rs.getLong("total_count")),
                Timestamp.from(since));
    }
}
