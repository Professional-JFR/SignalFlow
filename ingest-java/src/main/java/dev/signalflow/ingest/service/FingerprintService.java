package dev.signalflow.ingest.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a stable fingerprint for a log event.
 *
 * Fingerprint = SHA-256( service + "|" + normalizedMessage + "|" + SEVERITY )
 *
 * Including the service prevents unrelated errors from different services from
 * collapsing into the same group.  Severity is upper-cased so "error" and
 * "ERROR" produce the same fingerprint.
 */
@Service
public class FingerprintService {

    public String fingerprint(String service, String normalizedMessage, String severity) {
        String input = service + "|" + normalizedMessage + "|" + severity.toUpperCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE specification — this will never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
