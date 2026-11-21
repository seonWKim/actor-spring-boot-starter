package io.github.seonwkim.actor.autoconfigure.generation;

import io.github.seonwkim.actor.generation.ActivationResult;
import io.github.seonwkim.actor.generation.ActiveGeneration;
import java.security.Principal;
import javax.annotation.Nullable;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * The {@code generation} Actuator endpoint: reads the active generation and what this node reads, and activates a color with a
 * compare-and-set on the epoch. Reading is allowed by default; activating needs
 * {@code management.endpoint.generation.access=unrestricted} and an authenticated user (secure the
 * endpoint with Spring Security). An attempt without one is rejected with {@code UNAUTHENTICATED} and
 * audited like any other.
 */
@Endpoint(id = "generation", defaultAccess = Access.READ_ONLY)
public class GenerationEndpoint {

    /**
     * What a read returns.
     *
     * @param deployment the deployment name
     * @param color this node's color
     * @param active the active generation in the store, null before the first activation
     * @param node what this node reads now
     */
    public record Generation(String deployment, String color, @Nullable Active active, Node node) {}

    /**
     * This node's view. A node reuses a read of the store for {@code spring.actor.generation.refresh}, so
     * after an activation its {@code active} lags the store's for up to that long: a pipeline knows a
     * switch is done when every node reports the new epoch.
     *
     * @param active the generation this node reads now, the one it stamps and checks work with; null
     *     before the first activation
     * @param readAt when this node read it from the store (ISO-8601)
     * @param colorActive whether this node's color is the active one in that read
     * @param warm whether the {@code actorWarmup} health indicator is {@code UP}; null without one (not
     *     in cluster mode)
     */
    public record Node(@Nullable Active active, String readAt, boolean colorActive, @Nullable Boolean warm) {}

    /**
     * A generation as JSON. {@code activatedAt} is an ISO-8601 string: Actuator's own JSON mapper may not
     * know {@code java.time} types.
     */
    public record Active(String color, long epoch, String operationId, String activatedAt) {

        @Nullable static Active of(@Nullable ActiveGeneration generation) {
            return generation == null
                    ? null
                    : new Active(
                            generation.color(),
                            generation.epoch(),
                            generation.operationId(),
                            generation.activatedAt().toString());
        }
    }

    /** What an activation returns: the outcome and the generation it refers to (see {@link ActivationResult}). */
    public record Activation(ActivationResult.Outcome outcome, @Nullable Active generation) {

        static Activation of(ActivationResult result) {
            return new Activation(result.outcome(), Active.of(result.generation()));
        }
    }

    private final StoreDeploymentGeneration generation;

    @Nullable private final HealthIndicator warmup;

    public GenerationEndpoint(StoreDeploymentGeneration generation) {
        this(generation, null);
    }

    /** @param warmup the {@code actorWarmup} health indicator, null without one */
    public GenerationEndpoint(StoreDeploymentGeneration generation, @Nullable HealthIndicator warmup) {
        this.generation = generation;
        this.warmup = warmup;
    }

    /**
     * Reads the store for {@code active}, and this node's reused read for {@code node}. Reading does not
     * refresh the node's read, so polling the endpoint does not hurry a node along.
     */
    @ReadOperation
    public Generation read() {
        StoreDeploymentGeneration.Read read = generation.current();
        ActiveGeneration nodeActive = read.active().orElse(null);
        Node node = new Node(
                Active.of(nodeActive),
                read.at().toString(),
                nodeActive != null && nodeActive.color().equals(generation.color()),
                warmup == null ? null : Status.UP.equals(warmup.health().getStatus()));
        return new Generation(
                generation.deployment(),
                generation.color(),
                Active.of(generation.activeInStore().orElse(null)),
                node);
    }

    @WriteOperation
    public Activation activate(String color, long expectedEpoch, String operationId, SecurityContext securityContext) {
        Principal principal = securityContext.getPrincipal();
        if (principal == null) {
            ActivationResult rejected = new ActivationResult(
                    ActivationResult.Outcome.UNAUTHENTICATED,
                    generation.activeNow().orElse(null));
            generation.audit(color, expectedEpoch, operationId, null, rejected);
            return Activation.of(rejected);
        }
        return Activation.of(generation.activate(color, expectedEpoch, operationId, principal.getName()));
    }
}
