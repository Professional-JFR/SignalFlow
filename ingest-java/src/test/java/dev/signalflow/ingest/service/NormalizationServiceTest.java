package dev.signalflow.ingest.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {

    private final NormalizationService service = new NormalizationService();

    @Test
    void lowercasesMessage() {
        assertThat(service.normalize("ERROR Message")).isEqualTo("error message");
    }

    @Test
    void replacesUuid() {
        assertThat(service.normalize("trace f47ac10b-58cc-4372-a567-0e02b2c3d479 end"))
                .isEqualTo("trace <UUID> end");
    }

    @Test
    void replacesNumbers() {
        assertThat(service.normalize("order 12345 user 98"))
                .isEqualTo("order <NUM> user <NUM>");
    }

    @Test
    void collapsesWhitespace() {
        assertThat(service.normalize("too   many   spaces"))
                .isEqualTo("too many spaces");
    }

    @Test
    void trims() {
        assertThat(service.normalize("  leading and trailing  "))
                .isEqualTo("leading and trailing");
    }

    @Test
    void handlesNull() {
        assertThat(service.normalize(null)).isEqualTo("");
    }

    @Test
    void combinedTransformations() {
        String raw = "NullPointerException processing order 12345 for user 98 " +
                     "traceId=f47ac10b-58cc-4372-a567-0e02b2c3d479";
        assertThat(service.normalize(raw))
                .isEqualTo("nullpointerexception processing order <NUM> for user <NUM> traceid=<UUID>");
    }
}
