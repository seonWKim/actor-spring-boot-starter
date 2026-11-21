package io.github.seonwkim.actor.autoconfigure.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.actor.EnableActorSupport;
import io.github.seonwkim.actor.spi.GenerationStore;
import io.github.seonwkim.actor.test.InMemoryGenerationStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** In cluster mode the generation endpoint's {@code node.warm} comes from the starter's warm-up indicator. */
class GenerationEndpointWarmupTest {

    @SpringBootApplication
    @EnableActorSupport
    static class App {
        @Bean
        GenerationStore generationStore() {
            return new InMemoryGenerationStore();
        }
    }

    @Test
    void theNodeIsReportedWarmOnceItIsAnUpMember() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.actor.provider=cluster",
                        "spring.actor.remote.port=0",
                        "spring.actor.cluster.join-self=true",
                        "spring.actor.pekko.management.enabled=false",
                        "spring.actor.shutdown.exit-process=false",
                        "spring.actor.generation.color=blue")
                .run()) {
            GenerationEndpoint endpoint = context.getBean(GenerationEndpoint.class);

            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> assertThat(endpoint.read().node().warm()).isTrue());
        }
    }
}
