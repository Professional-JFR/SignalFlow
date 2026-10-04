package dev.signalflow.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPatternTest {

    @Test
    void consolePatternIsJson() throws Exception {
        PropertySource<?> ps = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        String pattern = (String) ps.getProperty("logging.pattern.console");
        assertThat(pattern).startsWith("{\"timestamp\"").contains("%X{correlationId");
    }
}
