package io.github.seonwkim.actor.autoconfigure.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.actor.spi.GenerationStore;
import io.github.seonwkim.actor.test.InMemoryGenerationStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * With management.health.actor.generation.enabled, readiness follows the active color: two colors share
 * one generation record, and activating green moves readiness from blue to green.
 */
class GenerationReadinessTest {

    /** The record both colors share, as a database or Redis would be. */
    private static final GenerationStore STORE = new InMemoryGenerationStore();

    @SpringBootApplication
    static class App {
        @Bean
        GenerationStore generationStore() {
            return STORE;
        }
    }

    private static ConfigurableApplicationContext start(String color, String... properties) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.actor.shutdown.exit-process=false",
                        "spring.actor.generation.name=readiness-test",
                        "spring.actor.generation.color=" + color,
                        "management.endpoint.health.probes.enabled=true",
                        "management.health.actor.generation.grace=0s")
                .properties(properties)
                .run();
    }

    private static Status status(ConfigurableApplicationContext context, String path) {
        HealthComponent health = context.getBean(HealthEndpoint.class).healthForPath(path);
        return health == null ? null : health.getStatus();
    }

    @Test
    void activatingAColorMovesReadinessToIt() {
        try (ConfigurableApplicationContext blue = start("blue", "management.health.actor.generation.enabled=true");
                ConfigurableApplicationContext green =
                        start("green", "management.health.actor.generation.enabled=true")) {
            blue.getBean(StoreDeploymentGeneration.class).activate("blue", 0, "deploy-1");
            await().atMost(Duration.ofSeconds(5)).until(() -> status(blue, "readiness") == Status.UP);
            assertThat(status(green, "readiness")).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(status(green, "liveness")).isEqualTo(Status.UP);

            green.getBean(StoreDeploymentGeneration.class).activate("green", 1, "deploy-2");

            await().atMost(Duration.ofSeconds(5)).until(() -> status(green, "readiness") == Status.UP);
            await().atMost(Duration.ofSeconds(5)).until(() -> status(blue, "readiness") == Status.OUT_OF_SERVICE);
            assertThat(status(blue, "liveness")).isEqualTo(Status.UP);
        }
    }

    @Test
    void withoutTheOptInReadinessIgnoresTheColor() {
        try (ConfigurableApplicationContext green = start("green")) {
            assertThat(status(green, "readiness")).isEqualTo(Status.UP);
            assertThat(status(green, "actorGeneration")).isNull();
        }
    }
}
