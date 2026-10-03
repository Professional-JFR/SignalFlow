package dev.signalflow.ingest.controller;

import dev.signalflow.ingest.dto.IngestResponse;
import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.service.LogIngestionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private final LogIngestionService service;

    public LogController(LogIngestionService service) {
        this.service = service;
    }

    /**
     * Ingest a single log event.
     *
     * <pre>POST /api/v1/logs</pre>
     *
     * Returns the computed fingerprint and normalized message so callers can
     * observe which de-duplication group the event was assigned to.
     */
    @PostMapping("/logs")
    public ResponseEntity<IngestResponse> ingestLog(@Valid @RequestBody LogRequest request) {
        log.info("Ingest request received service={} env={} severity={} requestId={}",
                request.service(), request.env(), request.severity(), request.requestId());
        long start = System.nanoTime();
        IngestResponse result = service.ingest(request);
        log.info("Ingest request completed fingerprint={} durationMs={}",
                result.fingerprint(), (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.ok(result);
    }
}
