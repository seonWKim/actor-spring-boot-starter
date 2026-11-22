package io.github.seonwkim.actor.autoconfigure.health;

import io.github.seonwkim.actor.autoconfigure.generation.GenerationConfiguration;
import java.util.Map;
import javax.annotation.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Puts {@code actorCluster} in the {@code readiness} health group in cluster mode, so a node takes
 * traffic only while it is an {@code Up} member: not before it joins, and not while it leaves. With
 * {@code management.health.actor.generation.enabled}, also {@code actorGeneration}, so a node takes
 * traffic only while its color is the active one.
 *
 * <p>It sets {@code management.endpoint.health.group.readiness.include} to {@code readinessState} plus
 * those members, as the lowest-precedence property source, only when the application sets no
 * {@code readiness.include} of its own (in any property source, its default properties included), Spring
 * Boot enables the probes (the property, or Kubernetes or Cloud Foundry) and the indicators exist.
 * {@code management.health.actor.readiness.enabled=false} turns it off.
 */
public class ActorReadinessDefaults implements EnvironmentPostProcessor {

    static final String SOURCE = "actorReadinessDefaults";
    static final String INCLUDE = "management.endpoint.health.group.readiness.include";
    static final String ENABLED = "management.health.actor.readiness.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String include = include(environment, application.getClassLoader());
        if (include != null) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.of(INCLUDE, include)));
        }
    }

    /** The readiness group's members, or null to leave the group to Spring Boot and the application. */
    @Nullable static String include(ConfigurableEnvironment environment, ClassLoader classLoader) {
        if (environment.containsProperty(INCLUDE)
                || environment.containsProperty(INCLUDE + "[0]")
                || !environment.getProperty("spring.actor.enabled", Boolean.class, true)
                || !environment.getProperty(ActorHealthConfiguration.ENABLED, Boolean.class, true)
                || !environment.getProperty(ENABLED, Boolean.class, true)
                || !probesEnabled(environment)
                || !ClassUtils.isPresent(ActorHealthConfiguration.ACTUATOR, classLoader)) {
            return null;
        }
        StringBuilder members = new StringBuilder("readinessState");
        if ("cluster".equals(environment.getProperty("spring.actor.provider"))
                && ClassUtils.isPresent("io.github.seonwkim.actor.internal.cluster.ClusterStateView", classLoader)) {
            members.append(",actorCluster");
        }
        if (environment.containsProperty("spring.actor.generation.color")
                && environment.getProperty(GenerationConfiguration.HEALTH_ENABLED, Boolean.class, false)) {
            members.append(",actorGeneration");
        }
        return members.toString().equals("readinessState") ? null : members.toString();
    }

    /** The same rule as Spring Boot's probes condition. */
    private static boolean probesEnabled(ConfigurableEnvironment environment) {
        for (String property :
                new String[] {"management.endpoint.health.probes.enabled", "management.health.probes.enabled"}) {
            String value = environment.getProperty(property);
            if (value != null) {
                return Boolean.parseBoolean(value);
            }
        }
        CloudPlatform platform = CloudPlatform.getActive(environment);
        return platform == CloudPlatform.KUBERNETES || platform == CloudPlatform.CLOUD_FOUNDRY;
    }
}
