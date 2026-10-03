package dev.signalflow.ingest.exception;

/** Thrown when a database operation fails (after retries for transient failures). */
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
