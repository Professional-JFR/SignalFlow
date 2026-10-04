package dev.signalflow.ingest.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Structured error body returned for all failures.
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> details,
        String correlationId,
        Instant timestamp) {}
