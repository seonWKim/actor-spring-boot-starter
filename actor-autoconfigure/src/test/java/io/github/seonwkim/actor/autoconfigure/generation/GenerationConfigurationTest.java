package io.github.seonwkim.actor.autoconfigure.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.generation.ActivationResult;
import io.github.seonwkim.actor.generation.ActiveGeneration;
import io.github.seonwkim.actor.generation.DeploymentGeneration;
import io.github.seonwkim.actor.spi.GenerationStore;
import io.github.seonwkim.actor.test.InMemoryGenerationStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Which {@link DeploymentGeneration} the application gets: the store's, the no-op one, or its own. */
class GenerationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(App.class)
            .withPropertyValues("spring.actor.shutdown.exit-process=false");

    @Test
    void withoutAColorTheGenerationIsAlwaysActiveAndNeverStale() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DeploymentGeneration.class);
            DeploymentGeneration generation = context.getBean(DeploymentGeneration.class);
            assertThat(generation.isActive()).isTrue();
            assertThat(generation.stamp()).isZero();
            generation.check(7);
        });
    }

    @Test
    void withAColorTheStoreGenerationIsTheOnlyOne() {
        runner.withPropertyValues("spring.actor.generation.color=blue").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DeploymentGeneration.class);
            assertThat(context.getBean(DeploymentGeneration.class)).isInstanceOf(StoreDeploymentGeneration.class);
        });
    }

    @Test
    void theApplicationsOwnGenerationReplacesTheNoOp() {
        runner.withUserConfiguration(OwnGeneration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DeploymentGeneration.class);
            assertThat(context.getBean(DeploymentGeneration.class)).isSameAs(OwnGeneration.GENERATION);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
        @Bean
        GenerationStore generationStore() {
            return new InMemoryGenerationStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnGeneration {
        static final DeploymentGeneration GENERATION = new DeploymentGeneration() {
            @Override
            public String color() {
                return "own";
            }

            @Override
            public Optional<ActiveGeneration> active() {
                return Optional.empty();
            }

            @Override
            public long stamp() {
                return 42;
            }

            @Override
            public void check(long epoch) {}

            @Override
            public ActivationResult activate(String color, long expectedEpoch, String operationId) {
                throw new UnsupportedOperationException();
            }
        };

        @Bean
        DeploymentGeneration ownGeneration() {
            return GENERATION;
        }
    }
}
