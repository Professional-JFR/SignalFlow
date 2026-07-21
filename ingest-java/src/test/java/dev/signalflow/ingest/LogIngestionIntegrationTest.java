package dev.signalflow.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LogIngestionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void ingestSingleLogReturnsFingerprint() throws Exception {
        String body = logJson("checkout", "prod", "ERROR",
                "NullPointerException processing order 12345");

        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fingerprint").isNotEmpty())
                .andExpect(jsonPath("$.normalizedMessage").value(
                        "nullpointerexception processing order <NUM>"));
    }

    @Test
    void topGroupsReflectsIngestedEvents() throws Exception {
        // Ingest the same logical error twice
        String body = logJson("payments", "prod", "ERROR", "DB connection timeout after 30s");
        mockMvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/groups/top?window=1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("1h"))
                .andExpect(jsonPath("$.groups[0].service").value("payments"))
                .andExpect(jsonPath("$.groups[0].count").value(2));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        // No message field
        String body = """
                {
                  "timestamp": "%s",
                  "service": "checkout",
                  "env": "prod",
                  "severity": "ERROR"
                }
                """.formatted(Instant.now());

        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.message").isNotEmpty());
    }

    @Test
    void missingTimestampReturns400() throws Exception {
        String body = """
                {
                  "service": "checkout",
                  "env": "prod",
                  "severity": "ERROR",
                  "message": "some error"
                }
                """;

        mockMvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timestamp").isNotEmpty());
    }

    @Test
    void unsupportedWindowReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/groups/top?window=bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- helpers ---

    private String logJson(String service, String env, String severity, String message) {
        return """
                {
                  "timestamp": "%s",
                  "service": "%s",
                  "env": "%s",
                  "severity": "%s",
                  "message": "%s"
                }
                """.formatted(Instant.now(), service, env, severity, message);
    }
}
