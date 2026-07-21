package dev.signalflow.ingest.service;

import dev.signalflow.ingest.dto.IngestResponse;
import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.dto.TopGroupsResponse;
import dev.signalflow.ingest.repository.LogEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final NormalizationService normalization;
    private final FingerprintService fingerprinting;
    private final LogEventRepository repository;

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
            MeterRegistry meterRegistry) {
        this.normalization = normalization;
        this.fingerprinting = fingerprinting;
        this.repository = repository;
        this.meterRegistry = meterRegistry;

        this.ingestEvents = meterRegistry.counter("signalflow.ingest.events");
        this.ingestErrors = meterRegistry.counter("signalflow.ingest.errors");
        this.ingestLatency = meterRegistry.timer("signalflow.ingest.latency");

        this.dedupTotal = meterRegistry.counter("signalflow.dedup.total");
        this.dedupHits  = meterRegistry.counter("signalflow.dedup.hits");
    }

    /**
     * Ingests a single validated log entry.
     * The raw event and the minute-bucket aggregate are written in one transaction.
     */
    @Transactional
    public IngestResponse ingest(LogRequest request) {
        return ingestLatency.record(() -> {
            try {
                String normalized = normalization.normalize(request.message());
                String fp = fingerprinting.fingerprint(request.service(), normalized, request.severity());

                dedupTotal.increment();
                boolean isKnown = repository.fingerprintExists(fp);
                if (isKnown) {
                    dedupHits.increment();
                }

                repository.saveLogEvent(request, normalized, fp);
                repository.upsertMinuteAggregate(request, normalized, fp);

                ingestEvents.increment();
                return new IngestResponse(fp, normalized);
            } catch (RuntimeException e) {
                ingestErrors.increment();
                throw e;
            }
        });
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
