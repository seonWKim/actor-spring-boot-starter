package io.github.seonwkim.actor.autoconfigure.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.autoconfigure.generation.StoreDeploymentGenerationTest.MutableClock;
import io.github.seonwkim.actor.generation.ActivationResult;
import io.github.seonwkim.actor.generation.ActiveGeneration;
import io.github.seonwkim.actor.spi.GenerationStore;
import io.github.seonwkim.actor.test.InMemoryGenerationStore;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * actorGeneration follows the active color: UP while active, OUT_OF_SERVICE otherwise, with a grace for
 * the color that just lost activation, and the last status while the store can't be read.
 */
class ActorGenerationHealthIndicatorTest {

    private static final Duration REFRESH = Duration.ofSeconds(1);
    private static final Duration GRACE = Duration.ofSeconds(30);

    private final AtomicBoolean storeDown = new AtomicBoolean();
    private final InMemoryGenerationStore records = new InMemoryGenerationStore();
    private final GenerationStore store = new GenerationStore() {
        @Override
        public Optional<ActiveGeneration> active(String deployment) {
            if (storeDown.get()) {
                throw new IllegalStateException("store down");
            }
            return records.active(deployment);
        }

        @Override
        public ActivationResult activate(String deployment, String color, long expectedEpoch, String operationId) {
            return records.activate(deployment, color, expectedEpoch, operationId);
        }
    };
    private final MutableClock clock = new MutableClock();

    private StoreDeploymentGeneration node(String color) {
        return new StoreDeploymentGeneration(store, "orders", color, REFRESH, clock, event -> {});
    }

    private ActorGenerationHealthIndicator indicator(StoreDeploymentGeneration node) {
        return new ActorGenerationHealthIndicator(node, clock, GRACE);
    }

    private Status status(ActorGenerationHealthIndicator indicator) {
        return indicator.health().getStatus();
    }

    @Test
    void theActiveColorIsUpAndTheColorThatLostItDrainsForTheGrace() {
        StoreDeploymentGeneration blue = node("blue");
        StoreDeploymentGeneration green = node("green");
        ActorGenerationHealthIndicator blueHealth = indicator(blue);
        ActorGenerationHealthIndicator greenHealth = indicator(green);
        blue.activate("blue", 0, "deploy-1");

        assertThat(status(blueHealth)).isEqualTo(Status.UP);
        assertThat(status(greenHealth)).as("starts inactive: no grace").isEqualTo(Status.OUT_OF_SERVICE);

        green.activate("green", 1, "deploy-2");
        clock.advance(REFRESH);

        assertThat(status(greenHealth)).isEqualTo(Status.UP);
        assertThat(status(blueHealth)).as("draining").isEqualTo(Status.UP);
        assertThat(blueHealth.health().getDetails())
                .containsEntry("colorActive", false)
                .containsKey("drainingUntil");

        clock.advance(GRACE);

        assertThat(status(blueHealth)).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(status(greenHealth)).isEqualTo(Status.UP);
    }

    @Test
    void anUnreachableStoreKeepsTheLastStatus() {
        StoreDeploymentGeneration blue = node("blue");
        StoreDeploymentGeneration green = node("green");
        ActorGenerationHealthIndicator blueHealth = indicator(blue);
        ActorGenerationHealthIndicator greenHealth = indicator(green);
        blue.activate("blue", 0, "deploy-1");
        assertThat(status(blueHealth)).isEqualTo(Status.UP);
        assertThat(status(greenHealth)).isEqualTo(Status.OUT_OF_SERVICE);

        storeDown.set(true);
        clock.advance(REFRESH.plus(GRACE));

        assertThat(status(blueHealth)).isEqualTo(Status.UP);
        assertThat(status(greenHealth)).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(blueHealth.health().getDetails()).containsEntry("store", "UNREACHABLE");
    }
}
