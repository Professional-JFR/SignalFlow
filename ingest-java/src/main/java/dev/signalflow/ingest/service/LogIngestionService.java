package dev.signalflow.ingest.service;

import dev.signalflow.ingest.dto.IngestResponse;
import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.dto.TopGroupsResponse;
import dev.signalflow.ingest.exception.DatabaseException;
import dev.signalflow.ingest.exception.ValidationException;
import dev.signalflow.ingest.repository.LogEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Orchestrates the ingestion pipeline:
 * validate (done by Spring) → normalize → fingerprint → persist → aggregate.
 *
 * <p>Micrometer metrics emitted:
 * <ul>
 *   <li>{@code signalflow.ingest.events} (counter) — total events accepted</li>
 *   <li>{@code signalflow.ingest.errors} (counter) — events that threw an exception</li>
 *   <li>{@code signalflow.ingest.latency} (timer) — end-to-end ingest latency</li>
 *   <li>{@code signalflow.dedup.total} (counter) — total fingerprint computations</li>
 *   <li>{@code signalflow.dedup.hits} (counter) — fingerprints that matched an existing group</li>
 *   <li>{@code signalflow.query.latency} (timer, tag bucket_mode) — topGroups query latency</li>
 * </ul>
 */
@Service
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    static final int MAX_ATTEMPTS = 3;
    static final long INITIAL_BACKOFF_MS = 50;

    private final NormalizationService normalization;
    private final FingerprintService fingerprinting;
    private final LogEventRepository repository;
    private final TransactionTemplate transactionTemplate;

    // --- ingestion metrics ---
    private final Counter ingestEvents;
    private final Counter ingestErrors;
    private final Timer ingestLatency;

    // --- dedup metrics ---
    private final Counter dedupTotal;
    private final Counter dedupHits;

    // --- query metrics ---
    private final MeterRegistry meterRegistry;

    public LogIngestionService(
            NormalizationService normalization,
            FingerprintService fingerprinting,
            LogEventRepository repository,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.normalization = normalization;
        this.fingerprinting = fingerprinting;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;

        this.ingestEvents = meterRegistry.counter("signalflow.ingest.events");
        this.ingestErrors = meterRegistry.counter("signalflow.ingest.errors");
        this.ingestLatency = meterRegistry.timer("signalflow.ingest.latency");

        this.dedupTotal = meterRegistry.counter("signalflow.dedup.total");
        this.dedupHits  = meterRegistry.counter("signalflow.dedup.hits");
    }

    /**
     * Ingests a single log entry.
     * The raw event and the minute-bucket aggregate are written in one transaction.
     * Transient database failures are retried with exponential backoff; each attempt
     * runs in its own transaction.
     */
    public IngestResponse ingest(LogRequest request) {
        validate(request);
        return ingestLatency.record(() -> {
            try {
                String normalized = normalization.normalize(request.message());
                String fp = fingerprinting.fingerprint(request.service(), normalized, request.severity());
                log.debug("Computed fingerprint={} service={}", fp, request.service());

                persistWithRetry(request, normalized, fp);

                ingestEvents.increment();
                log.info("Event ingested fingerprint={} service={} severity={}",
                        fp, request.service(), request.severity());
                return new IngestResponse(fp, normalized);
            } catch (RuntimeException e) {
                ingestErrors.increment();
                throw e;
            }
        });
    }

    private void validate(LogRequest request) {
        if (request == null) {
            throw new ValidationException("Request body is required.");
        }
        if (request.timestamp() == null) {
            throw new ValidationException("timestamp is required (ISO-8601, e.g. 2024-01-01T00:00:00Z).");
        }
        requireText(request.service(), "service");
        requireText(request.env(), "env");
        requireText(request.severity(), "severity");
        requireText(request.message(), "message");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            log.warn("Validation failed: {} is missing", field);
            throw new ValidationException(field + " is required and must not be blank.");
        }
    }

    private void persistWithRetry(LogRequest request, String normalized, String fp) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> persist(request, normalized, fp));
                return;
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new DatabaseException("Database unavailable after " + attempt + " attempts", e);
                }
                log.warn("Transient database failure attempt={}/{} backoffMs={}",
                        attempt, MAX_ATTEMPTS, backoff, e);
                sleep(backoff);
                backoff *= 2;
            } catch (DataAccessException e) {
                throw new DatabaseException("Database operation failed", e);
            }
        }
    }

    private void persist(LogRequest request, String normalized, String fp) {
        dedupTotal.increment();
        boolean isKnown = repository.fingerprintExists(fp);
        if (isKnown) {
            dedupHits.increment();
            log.debug("Duplicate fingerprint={}", fp);
        }
        repository.saveLogEvent(request, normalized, fp);
        repository.upsertMinuteAggregate(request, normalized, fp);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while retrying database operation", ie);
        }
    }

    /**
     * Returns the top fingerprint groups observed in the given time window.
     *
     * @param window one of: {@code 15m}, {@code 1h}, {@code 24h}
     */
    public TopGroupsResponse topGroups(String window) {
        String bucketMode = "unbucketed";
        Timer queryTimer = meterRegistry.timer("signalflow.query.latency",
                "bucket_mode", bucketMode);
        return queryTimer.record(() -> {
            Duration duration = parseWindow(window);
            Instant since = Instant.now().minus(duration);
            List<TopGroupsResponse.GroupEntry> groups = repository.topGroups(since);
            return new TopGroupsResponse(window, groups);
        });
    }

    private Duration parseWindow(String window) {
        return switch (window) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h"  -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            default -> throw new IllegalArgumentException(
                    "Unsupported window '" + window + "'. Valid values: 15m, 1h, 24h.");
        };
    }
}
