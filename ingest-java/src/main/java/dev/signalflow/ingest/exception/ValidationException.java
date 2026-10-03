package dev.signalflow.ingest.exception;

/** Thrown when request data is invalid. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
