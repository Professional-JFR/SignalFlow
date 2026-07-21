package dev.signalflow.ingest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Payload accepted by POST /api/v1/logs.
 * timestamp, service, env, severity, and message are required.
 * traceId and requestId are optional.
 */
public record LogRequest(
        @NotNull(message = "timestamp is required")
        Instant timestamp,

        @NotBlank(message = "service is required")
        String service,

        @NotBlank(message = "env is required")
        String env,

        @NotBlank(message = "severity is required")
        String severity,

        @NotBlank(message = "message is required")
        String message,

        String traceId,
        String requestId
) {}
