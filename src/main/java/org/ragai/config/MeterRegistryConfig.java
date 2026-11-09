package org.ragai.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MeterRegistryConfig {

    @Bean
    @Primary
    public MeterRegistry meterRegistry(MeterRegistry existing) {
        return existing;
    }

    public static final String STREAM_REQUESTS = "rag.stream.requests";
    public static final String STREAM_TTFT = "rag.stream.ttft";
    public static final String STREAM_DURATION = "rag.stream.duration";

    public static io.micrometer.core.instrument.Timer streamTtftTimer(MeterRegistry registry) {
        return io.micrometer.core.instrument.Timer.builder(STREAM_TTFT)
                .description("First token latency for streaming responses")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        java.time.Duration.ofMillis(200),
                        java.time.Duration.ofMillis(500),
                        java.time.Duration.ofMillis(1000),
                        java.time.Duration.ofMillis(2000),
                        java.time.Duration.ofMillis(5000)
                )
                .register(registry);
    }

    public static io.micrometer.core.instrument.Timer streamDurationTimer(MeterRegistry registry) {
        return io.micrometer.core.instrument.Timer.builder(STREAM_DURATION)
                .description("Total duration of streaming responses")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        java.time.Duration.ofSeconds(3),
                        java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofSeconds(20),
                        java.time.Duration.ofSeconds(30)
                )
                .register(registry);
    }
}