package io.github.seonwkim.actor.autoconfigure.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.autoconfigure.generation.StoreDeploymentGenerationTest.MutableClock;
import io.github.seonwkim.actor.test.InMemoryGenerationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** The endpoint's read reports this node's view next to the store's, without refreshing it. */
class GenerationEndpointReadTest {

    private final InMemoryGenerationStore store = new InMemoryGenerationStore();
    private final MutableClock clock = new MutableClock();

    private StoreDeploymentGeneration node(String color) {
        return new StoreDeploymentGeneration(store, "orders", color, Duration.ofSeconds(1), clock, event -> {});
    }

    @Test
    void aNodeReportsTheOldEpochUntilItsReadExpiresThenTheNewOneWithTheReadTime() {
        StoreDeploymentGeneration blue = node("blue");
        StoreDeploymentGeneration green = node("green");
        GenerationEndpoint blueEndpoint = new GenerationEndpoint(blue);
        blue.activate("blue", 0, "deploy-1");
        Instant firstRead = clock.instant();

        green.activate("green", 1, "deploy-2");
        clock.advance(Duration.ofMillis(500));
        GenerationEndpoint.Generation before = blueEndpoint.read();
        GenerationEndpoint.Generation again = blueEndpoint.read();
        clock.advance(Duration.ofMillis(500));
        GenerationEndpoint.Generation after = blueEndpoint.read();

        assertThat(before.active().epoch()).as("the store").isEqualTo(2);
        assertThat(before.node().active().epoch()).isEqualTo(1);
        assertThat(before.node().readAt()).isEqualTo(firstRead.toString());
        assertThat(before.node().colorActive()).isTrue();
        assertThat(again.node().active().epoch())
                .as("reading the endpoint does not refresh")
                .isEqualTo(1);
        assertThat(after.node().active().epoch()).isEqualTo(2);
        assertThat(after.node().readAt()).isEqualTo(clock.instant().toString());
        assertThat(after.node().colorActive()).isFalse();
    }

    @Test
    void theWarmFlagFollowsTheWarmUpIndicator() {
        AtomicReference<Status> warmup = new AtomicReference<>(Status.OUT_OF_SERVICE);
        GenerationEndpoint endpoint = new GenerationEndpoint(
                node("blue"), () -> Health.status(warmup.get()).build());

        assertThat(endpoint.read().node().warm()).isFalse();
        warmup.set(Status.UP);
        assertThat(endpoint.read().node().warm()).isTrue();
        assertThat(new GenerationEndpoint(node("blue")).read().node().warm())
                .as("without an indicator")
                .isNull();
    }
}
