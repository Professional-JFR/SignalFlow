package dev.signalflow.ingest.exception;

/** Thrown when a log message cannot be normalized. */
public class NormalizationException extends RuntimeException {
    public NormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
