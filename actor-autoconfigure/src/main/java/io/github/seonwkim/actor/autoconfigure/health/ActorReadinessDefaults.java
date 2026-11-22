package io.github.seonwkim.actor.autoconfigure.health;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Puts {@code actorCluster} in the {@code readiness} health group in cluster mode, so a node takes
 * traffic only while it is an {@code Up} member: not before it joins, and not while it leaves.
 *
 * <p>It adds {@code management.endpoint.health.group.readiness.include=readinessState,actorCluster} as
 * the lowest-precedence property source, only when the application sets no {@code readiness.include} of
 * its own (in any property source, its default properties included), Spring Boot enables the probes (the
 * property, or Kubernetes or Cloud Foundry) and the {@code actorCluster} indicator exists.
 * {@code management.health.actor.readiness.enabled=false} turns this off.
 */
public class ActorReadinessDefaults implements EnvironmentPostProcessor {

    static final String SOURCE = "actorReadinessDefaults";
    static final String INCLUDE = "management.endpoint.health.group.readiness.include";
    static final String ENABLED = "management.health.actor.readiness.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (applies(environment, application.getClassLoader())) {
            environment
                    .getPropertySources()
                    .addLast(new MapPropertySource(SOURCE, Map.of(INCLUDE, "readinessState,actorCluster")));
        }
    }

    static boolean applies(ConfigurableEnvironment environment, ClassLoader classLoader) {
        return !environment.containsProperty(INCLUDE)
                && !environment.containsProperty(INCLUDE + "[0]")
                && environment.getProperty("spring.actor.enabled", Boolean.class, true)
                && "cluster".equals(environment.getProperty("spring.actor.provider"))
                && environment.getProperty(ActorHealthConfiguration.ENABLED, Boolean.class, true)
                && environment.getProperty(ENABLED, Boolean.class, true)
                && probesEnabled(environment)
                && ClassUtils.isPresent(ActorHealthConfiguration.ACTUATOR, classLoader)
                && ClassUtils.isPresent("io.github.seonwkim.actor.internal.cluster.ClusterStateView", classLoader);
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
