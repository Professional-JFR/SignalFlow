package dev.signalflow.ingest.controller;

import dev.signalflow.ingest.dto.IngestResponse;
import dev.signalflow.ingest.dto.LogRequest;
import dev.signalflow.ingest.service.LogIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LogController {

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
        IngestResponse result = service.ingest(request);
        return ResponseEntity.ok(result);
    }
}
