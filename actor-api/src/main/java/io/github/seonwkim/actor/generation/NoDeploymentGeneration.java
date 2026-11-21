package io.github.seonwkim.actor.generation;

import java.util.Optional;

/** The generation of an application without {@code spring.actor.generation.color}: always current. */
final class NoDeploymentGeneration implements DeploymentGeneration {

    static final NoDeploymentGeneration INSTANCE = new NoDeploymentGeneration();

    private NoDeploymentGeneration() {}

    @Override
    public String color() {
        return "";
    }

    @Override
    public Optional<ActiveGeneration> active() {
        return Optional.empty();
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public long stamp() {
        return 0;
    }

    @Override
    public void check(long epoch) {}

    @Override
    public ActivationResult activate(String color, long expectedEpoch, String operationId) {
        throw new IllegalStateException(
                "There is no generation to activate: set spring.actor.generation.color and spring.actor.generation.store");
    }
}
