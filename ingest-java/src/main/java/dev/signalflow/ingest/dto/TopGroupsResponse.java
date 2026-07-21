package dev.signalflow.ingest.dto;

import java.util.List;

/**
 * Response for GET /api/v1/groups/top.
 */
public record TopGroupsResponse(String window, List<GroupEntry> groups) {

    public record GroupEntry(
            String fingerprint,
            String service,
            String env,
            String severity,
            String normalizedMessage,
            String sampleMessage,
            long count
    ) {}
}
