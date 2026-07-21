package dev.signalflow.ingest.controller;

import dev.signalflow.ingest.dto.TopGroupsResponse;
import dev.signalflow.ingest.service.LogIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GroupController {

    private final LogIngestionService service;

    public GroupController(LogIngestionService service) {
        this.service = service;
    }

    /**
     * Returns the top fingerprint groups for a recent time window.
     *
     * <pre>GET /api/v1/groups/top?window=15m</pre>
     *
     * @param window time window — one of {@code 15m}, {@code 1h}, {@code 24h}.
     *               Defaults to {@code 15m}.
     */
    @GetMapping("/groups/top")
    public TopGroupsResponse topGroups(
            @RequestParam(name = "window", defaultValue = "15m") String window) {
        return service.topGroups(window);
    }
}
