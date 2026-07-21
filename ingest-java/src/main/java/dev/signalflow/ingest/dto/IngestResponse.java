package dev.signalflow.ingest.dto;

/**
 * Response returned after a successful log ingestion.
 */
public record IngestResponse(String fingerprint, String normalizedMessage) {}
