package dev.signalflow.ingest.service;

import dev.signalflow.ingest.dto.IngestResponse;
import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.exception.DatabaseException;
import dev.signalflow.ingest.exception.ValidationException;
import dev.signalflow.ingest.repository.LogEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogIngestionServiceTest {

    private LogEventRepository repository;
    private SimpleMeterRegistry registry;
    private LogIngestionService service;

    @BeforeEach
    void setUp() {
        repository = mock(LogEventRepository.class);
        registry = new SimpleMeterRegistry();
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            Consumer<Object> c = inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new LogIngestionService(
                new NormalizationService(), new FingerprintService(), repository, tx, registry);
    }

    private LogRequest request(String message) {
        return new LogRequest(Instant.parse("2024-01-01T00:00:00Z"), "svc", "prod", "error",
                message, null, "req-1");
    }

    @Test
    void successfulIngestPersistsEventAndAggregate() {
        IngestResponse r = service.ingest(request("Order 42 failed"));

        assertThat(r.normalizedMessage()).isEqualTo("order <NUM> failed");
        assertThat(r.fingerprint()).hasSize(64);
        verify(repository).saveLogEvent(any(), anyString(), anyString());
        verify(repository).upsertMinuteAggregate(any(), anyString(), anyString());
        assertThat(registry.counter("signalflow.ingest.events").count()).isEqualTo(1.0);
    }

    @Test
    void fingerprintIsConsistentForEquivalentMessages() {
        String a = service.ingest(request("Order 1 failed")).fingerprint();
        String b = service.ingest(request("order   999 FAILED")).fingerprint();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void duplicateIsCountedAsDedupHit() {
        when(repository.fingerprintExists(anyString())).thenReturn(false, true);
        service.ingest(request("boom"));
        service.ingest(request("boom"));
        assertThat(registry.counter("signalflow.dedup.total").count()).isEqualTo(2.0);
        assertThat(registry.counter("signalflow.dedup.hits").count()).isEqualTo(1.0);
    }

    @Test
    void missingFieldsAreRejected() {
        LogRequest bad = new LogRequest(Instant.now(), " ", "prod", "error", "m", null, null);
        assertThatThrownBy(() -> service.ingest(bad))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("service");
        LogRequest noTs = new LogRequest(null, "s", "prod", "error", "m", null, null);
        assertThatThrownBy(() -> service.ingest(noTs)).isInstanceOf(ValidationException.class);
        verify(repository, never()).saveLogEvent(any(), anyString(), anyString());
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        doThrow(new TransientDataAccessResourceException("down"))
                .doNothing()
                .when(repository).saveLogEvent(any(), anyString(), anyString());
        service.ingest(request("x"));
        verify(repository, times(2)).saveLogEvent(any(), anyString(), anyString());
    }

    @Test
    void persistentTransientFailureBecomesDatabaseException() {
        doThrow(new TransientDataAccessResourceException("down"))
                .when(repository).saveLogEvent(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.ingest(request("x"))).isInstanceOf(DatabaseException.class);
        verify(repository, times(LogIngestionService.MAX_ATTEMPTS))
                .saveLogEvent(any(), anyString(), anyString());
        assertThat(registry.counter("signalflow.ingest.errors").count()).isEqualTo(1.0);
    }

    @Test
    void nonTransientFailureIsNotRetried() {
        doThrow(new DataIntegrityViolationException("bad"))
                .when(repository).saveLogEvent(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.ingest(request("x"))).isInstanceOf(DatabaseException.class);
        verify(repository, times(1)).saveLogEvent(any(), anyString(), anyString());
    }
}
