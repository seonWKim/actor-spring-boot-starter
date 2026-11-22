package io.github.seonwkim.actor.autoconfigure.generation;

import io.github.seonwkim.actor.generation.ActiveGeneration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nullable;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Reported as the {@code actorGeneration} component: {@code UP} while this node's color is the active
 * generation, {@code OUT_OF_SERVICE} while it isn't, never {@code DOWN} (an inactive color is healthy, it
 * just should not take traffic). In the readiness group it lets a load balancer follow an activation: one
 * target group holds both colors, and activating a color moves the traffic.
 *
 * <p>A color that loses activation stays {@code UP} for {@code grace}, so the load balancer admits the new
 * color before it drains the old one. A node that starts with its color inactive gets no grace. While the
 * store can't be read, the last status stays, so a store outage doesn't take both colors out at once.
 */
class ActorGenerationHealthIndicator implements HealthIndicator {

    private final StoreDeploymentGeneration generation;
    private final Clock clock;
    private final Duration grace;

    /** Whether the last read found this color active. */
    private boolean wasActive;

    /** When this color lost activation, while the grace runs; null otherwise. */
    @Nullable private Instant lostAt;

    @Nullable private Status last;

    ActorGenerationHealthIndicator(StoreDeploymentGeneration generation, Clock clock, Duration grace) {
        this.generation = generation;
        this.clock = clock;
        this.grace = grace;
    }

    @Override
    public synchronized Health health() {
        ActiveGeneration active;
        try {
            active = generation.current().active().orElse(null);
        } catch (RuntimeException e) {
            return Health.status(last != null ? last : Status.OUT_OF_SERVICE)
                    .withDetail("color", generation.color())
                    .withDetail("store", "UNREACHABLE")
                    .build();
        }
        boolean colorActive = active != null && active.color().equals(generation.color());
        Instant now = clock.instant();
        if (colorActive) {
            lostAt = null;
        } else if (wasActive) {
            lostAt = now;
        }
        wasActive = colorActive;
        Instant lost = lostAt;
        @Nullable Instant drainingUntil = lost == null || !now.isBefore(lost.plus(grace)) ? null : lost.plus(grace);
        boolean draining = drainingUntil != null;
        last = colorActive || draining ? Status.UP : Status.OUT_OF_SERVICE;

        Health.Builder health = Health.status(last)
                .withDetail("color", generation.color())
                .withDetail("colorActive", colorActive)
                .withDetail("store", "REACHABLE");
        if (active != null) {
            health.withDetail("activeColor", active.color()).withDetail("epoch", active.epoch());
        }
        if (drainingUntil != null) {
            health.withDetail("drainingUntil", drainingUntil.toString());
        }
        return health.build();
    }
}
