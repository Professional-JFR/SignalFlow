package dev.signalflow.ingest.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintServiceTest {

    private final FingerprintService service = new FingerprintService();

    @Test
    void sameInputProducesSameFingerprint() {
        String fp1 = service.fingerprint("checkout", "nullpointerexception", "ERROR");
        String fp2 = service.fingerprint("checkout", "nullpointerexception", "ERROR");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void differentServiceProducesDifferentFingerprint() {
        String fp1 = service.fingerprint("checkout", "nullpointerexception", "ERROR");
        String fp2 = service.fingerprint("payment", "nullpointerexception", "ERROR");
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void differentMessageProducesDifferentFingerprint() {
        String fp1 = service.fingerprint("svc", "connection refused", "ERROR");
        String fp2 = service.fingerprint("svc", "timeout exceeded", "ERROR");
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void differentSeverityProducesDifferentFingerprint() {
        String fp1 = service.fingerprint("svc", "disk full", "ERROR");
        String fp2 = service.fingerprint("svc", "disk full", "WARN");
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void severityCaseInsensitive() {
        String fp1 = service.fingerprint("svc", "disk full", "ERROR");
        String fp2 = service.fingerprint("svc", "disk full", "error");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void fingerprintIs64CharLowercaseHex() {
        String fp = service.fingerprint("svc", "some message", "ERROR");
        assertThat(fp).hasSize(64).matches("[0-9a-f]+");
    }
}
