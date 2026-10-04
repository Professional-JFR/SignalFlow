package dev.signalflow.ingest.controller;

import dev.signalflow.ingest.dto.ErrorResponse;
import dev.signalflow.ingest.exception.DatabaseException;
import dev.signalflow.ingest.exception.ErrorCode;
import dev.signalflow.ingest.exception.NormalizationException;
import dev.signalflow.ingest.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates failures into structured error responses
 * ({@code code}, {@code message}, {@code details}, {@code correlationId}, {@code timestamp}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean-validation failures: missing/blank required fields. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value",
                        (first, second) -> first));
        log.warn("Request validation failed fields={}", fieldErrors.keySet());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Request validation failed. Fix the listed fields and retry.", fieldErrors);
    }

    /** Malformed JSON or wrong field types (e.g. non-ISO timestamp). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "Malformed JSON or invalid field type. timestamp must be ISO-8601 (e.g. 2024-01-01T00:00:00Z).",
                Map.of());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleCustomValidation(ValidationException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), Map.of());
    }

    /** Unsupported window parameter value. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NormalizationException.class)
    public ResponseEntity<ErrorResponse> handleNormalization(NormalizationException ex) {
        log.error("Normalization failed", ex);
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.NORMALIZATION_ERROR,
                "Log message could not be normalized.", Map.of());
    }

    @ExceptionHandler(DatabaseException.class)
    public ResponseEntity<ErrorResponse> handleDatabase(DatabaseException ex) {
        log.error("Database operation failed", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DATABASE_ERROR,
                "Storage is temporarily unavailable. Please retry later.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred.", Map.of());
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, ErrorCode code, String message, Map<String, String> details) {
        ErrorResponse body = new ErrorResponse(
                code.name(), message, details, MDC.get("correlationId"), Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}
