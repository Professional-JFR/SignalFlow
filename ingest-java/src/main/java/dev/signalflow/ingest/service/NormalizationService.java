package dev.signalflow.ingest.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Normalizes raw log messages into a stable, canonical form used for fingerprinting.
 *
 * Rules applied (in order):
 * 1. Lowercase the entire message.
 * 2. Replace UUIDs with {@code <UUID>}.
 * 3. Replace standalone integers with {@code <NUM>}.
 * 4. Collapse runs of whitespace to a single space and trim.
 */
@Service
public class NormalizationService {

    // Standard 8-4-4-4-12 UUID form
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // Word-boundary integers (standalone numbers)
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");

    // Runs of whitespace (spaces, tabs, newlines)
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public String normalize(String message) {
        if (message == null) {
            return "";
        }
        String result = message.toLowerCase();
        result = UUID_PATTERN.matcher(result).replaceAll("<UUID>");
        result = NUMBER_PATTERN.matcher(result).replaceAll("<NUM>");
        result = WHITESPACE_PATTERN.matcher(result).replaceAll(" ");
        return result.trim();
    }
}
