package io.github.seonwkim.actor.generation;

import io.github.seonwkim.actor.SpringActorBehavior;
import io.github.seonwkim.actor.SpringBehaviorContext;
import io.github.seonwkim.actor.delivery.StaleGenerationException;
import java.util.Optional;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blue-green activation and fencing for this deployment. Each deployment (color) runs with
 * {@code spring.actor.generation.color} set; the active generation (color and epoch) is one record in
 * the shared store ({@code spring.actor.generation.store}), and the pipeline changes it with
 * {@link #activate}, usually through the {@code generation} Actuator endpoint.
 *
 * <p>Fencing: stamp work with {@link #stamp()} where it starts and {@link #check} it where it runs.
 * Work stamped by a color that was replaced fails with {@link StaleGenerationException}, a retryable
 * outcome, even after that color is activated again: the epoch only grows.
 *
 * <p>To drop stale messages without catching the exception, let the message implement
 * {@link GenerationStamped} and wrap its handler with {@link #ifCurrent}.
 *
 * <p>Reads of the active generation are reused for {@code spring.actor.generation.refresh} (1 second by
 * default), so a node sees an activation made elsewhere within that time.
 *
 * <p>Without {@code spring.actor.generation.color}, the starter's bean is {@link #none()}, so code that
 * stamps and checks runs the same with and without blue-green.
 */
public interface DeploymentGeneration {

    /**
     * The generation of an application that runs without blue-green: always active, {@link #stamp()}
     * returns 0, {@link #check} never throws, and {@link #activate} fails with
     * {@link IllegalStateException}.
     *
     * @since 1.2.0
     */
    static DeploymentGeneration none() {
        return NoDeploymentGeneration.INSTANCE;
    }

    /** This node's color ({@code spring.actor.generation.color}). */
    String color();

    /** The active generation, or empty before the first activation. */
    Optional<ActiveGeneration> active();

    /** Whether this node's color is the active one. */
    default boolean isActive() {
        return active().map(active -> active.color().equals(color())).orElse(false);
    }

    /**
     * The epoch to stamp new work with.
     *
     * @throws StaleGenerationException if this node's color is not active
     */
    long stamp();

    /**
     * Checks work stamped with {@code epoch}.
     *
     * @throws StaleGenerationException unless {@code epoch} is the active epoch
     */
    void check(long epoch);

    /**
     * Makes {@code color} active if the active epoch is {@code expectedEpoch} (0 before the first
     * activation): a compare-and-set, so of several deployments that expect the same epoch exactly one
     * wins. Retrying the same {@code operationId} with the same color and epoch returns the generation it
     * activated. Every attempt publishes an {@link ActivationEvent}.
     */
    ActivationResult activate(String color, long expectedEpoch, String operationId);

    /**
     * Wraps a message handler so that it runs only for a message of the active epoch. A stale message
     * (see {@link #check}) is dropped with a warning on the actor's log (when the handler's state is the
     * {@link SpringBehaviorContext}), and the actor keeps its behavior.
     *
     * <pre>{@code
     * .onMessage(ChargeOrder.class, generation.ifCurrent((ctx, msg) -> {
     *     payments.charge(msg.orderId());
     *     return SpringActorBehavior.same();
     * }))
     * }</pre>
     *
     * <p>For a {@code ReliableDelivery} consumer, call {@link #check} in the handler instead: the
     * {@link StaleGenerationException} it throws tells the sender to retry, while a dropped message is
     * acknowledged.
     *
     * @since 1.2.0
     */
    default <S, M extends GenerationStamped, C> BiFunction<S, M, SpringActorBehavior<C>> ifCurrent(
            BiFunction<S, M, SpringActorBehavior<C>> handler) {
        return (state, message) -> {
            try {
                check(message.epoch());
            } catch (StaleGenerationException e) {
                Logger log = state instanceof SpringBehaviorContext<?> context
                        ? context.getLog()
                        : LoggerFactory.getLogger(DeploymentGeneration.class);
                log.warn("Dropped {}: {}", message.getClass().getSimpleName(), e.getMessage());
                return SpringActorBehavior.same();
            }
            return handler.apply(state, message);
        };
    }
}
