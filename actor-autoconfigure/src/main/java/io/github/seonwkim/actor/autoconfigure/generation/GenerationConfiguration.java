package io.github.seonwkim.actor.autoconfigure.generation;

import io.github.seonwkim.actor.SpringActorSystem;
import io.github.seonwkim.actor.autoconfigure.ActorConfiguration;
import io.github.seonwkim.actor.autoconfigure.ActorProperties;
import io.github.seonwkim.actor.autoconfigure.ConditionalOnActorEnabled;
import io.github.seonwkim.actor.autoconfigure.cluster.StoreConfiguration;
import io.github.seonwkim.actor.generation.ActivationEvent;
import io.github.seonwkim.actor.generation.DeploymentGeneration;
import io.github.seonwkim.actor.spi.GenerationStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Blue-green activation and fencing ({@link DeploymentGeneration}) when {@code spring.actor.generation.color}
 * is set, and {@link DeploymentGeneration#none()} when it is not and the application defines no
 * {@code DeploymentGeneration} of its own. With a color, the generation runs on the {@link GenerationStore}
 * that {@code spring.actor.generation.store} picks or the application defines. With Actuator on the classpath, also the {@code generation} endpoint, and with
 * Micrometer the {@code actor.generation.activations} meter.
 *
 * <p>Each nested class carries all of its own conditions, and none needs an Actuator or Micrometer class
 * to evaluate.
 */
@AutoConfiguration(after = {ActorConfiguration.class, StoreConfiguration.class})
@ConditionalOnActorEnabled
public class GenerationConfiguration {

    static final String COLOR = "spring.actor.generation.color";
    static final String METER = "actor.generation.activations";
    static final String WARMUP_INDICATOR = "actorWarmupHealthIndicator";
    public static final String HEALTH_ENABLED = "management.health.actor.generation.enabled";
    static final String HEALTH_GRACE = "management.health.actor.generation.grace";

    @Bean
    @ConditionalOnProperty(name = COLOR)
    public StoreDeploymentGeneration deploymentGeneration(
            ObjectProvider<GenerationStore> store,
            ActorProperties properties,
            SpringActorSystem actorSystem,
            ApplicationEventPublisher events) {
        GenerationStore generationStore = store.getIfUnique(() -> {
            throw new IllegalStateException(
                    COLOR + " needs one GenerationStore bean: set spring.actor.generation.store"
                            + " to jdbc (with actor-jdbc) or redis (with actor-redis), or define your own GenerationStore bean");
        });
        ActorProperties.Generation generation = properties.getGeneration();
        String name = generation.getName();
        return new StoreDeploymentGeneration(
                generationStore,
                name != null ? name : actorSystem.getName(),
                Objects.requireNonNull(generation.getColor()),
                generation.getRefresh(),
                Clock.systemUTC(),
                events);
    }

    /**
     * Declared after {@link #deploymentGeneration}, so that it sees that bean: Spring registers a
     * configuration's bean methods in order.
     */
    @Bean
    @ConditionalOnMissingBean(DeploymentGeneration.class)
    public DeploymentGeneration noDeploymentGeneration() {
        return DeploymentGeneration.none();
    }

    /** Counts each attempt by outcome. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(name = COLOR)
    static class Meter {

        @Bean
        ApplicationListener<ActivationEvent> generationActivationMeter(ObjectProvider<MeterRegistry> registries) {
            return event -> registries.forEach(registry -> Counter.builder(METER)
                    .description("Blue-green activation attempts, by outcome")
                    .tag("outcome", event.result().outcome().name())
                    .register(registry)
                    .increment());
        }
    }

    /**
     * The {@code actorGeneration} health indicator, opt-in: on a standby color it makes the overall health
     * {@code OUT_OF_SERVICE} too. With the probes on, the readiness group includes it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.AbstractHealthIndicator")
    @ConditionalOnProperty(name = COLOR)
    @ConditionalOnProperty(name = HEALTH_ENABLED, havingValue = "true")
    static class Health {

        @Bean
        ActorGenerationHealthIndicator actorGenerationHealthIndicator(
                StoreDeploymentGeneration generation, Environment environment) {
            return new ActorGenerationHealthIndicator(
                    generation,
                    Clock.systemUTC(),
                    environment.getProperty(HEALTH_GRACE, Duration.class, Duration.ofSeconds(30)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnProperty(name = COLOR)
    static class Endpoint {

        /** Looks the warm-up indicator up by name: its class needs actor-cluster, which may be missing. */
        @Bean
        GenerationEndpoint generationEndpoint(
                StoreDeploymentGeneration generation,
                BeanFactory beans,
                ObjectProvider<BuildProperties> buildProperties,
                ObjectProvider<GitProperties> gitProperties) {
            HealthIndicator warmup = beans.containsBean(WARMUP_INDICATOR)
                    ? beans.getBean(WARMUP_INDICATOR, HealthIndicator.class)
                    : null;
            return new GenerationEndpoint(
                    generation, warmup, build(buildProperties.getIfUnique(), gitProperties.getIfUnique()));
        }

        /** The node's build from Spring Boot's build and git info; null without either. */
        @Nullable static GenerationEndpoint.Build build(@Nullable BuildProperties build, @Nullable GitProperties git) {
            if (build == null && git == null) {
                return null;
            }
            return new GenerationEndpoint.Build(
                    build == null ? null : build.getVersion(), git == null ? null : git.getShortCommitId());
        }

        @Bean
        GenerationEndpointWebExtension generationEndpointWebExtension(GenerationEndpoint endpoint) {
            return new GenerationEndpointWebExtension(endpoint);
        }
    }
}
