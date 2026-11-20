package io.github.seonwkim.actor.generation;

/**
 * A message that carries the epoch it was stamped with, so that a receiver can drop it once another
 * generation is active (see {@link DeploymentGeneration#ifCurrent}). A record with an {@code epoch}
 * component implements it as is:
 *
 * <pre>{@code
 * record ChargeOrder(String orderId, long epoch) implements Command, GenerationStamped {}
 *
 * new ChargeOrder(orderId, generation.stamp())
 * }</pre>
 *
 * @since 1.2.0
 */
public interface GenerationStamped {

    /** The epoch the sender stamped this message with, from {@link DeploymentGeneration#stamp()}. */
    long epoch();
}
