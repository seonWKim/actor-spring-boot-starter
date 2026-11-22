package io.github.seonwkim.actor.autoconfigure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.actor.EnableActorSupport;
import io.github.seonwkim.actor.SpringActorSystem;
import java.time.Duration;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Leave;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * In cluster mode with probes enabled, the readiness group includes actorCluster: a node takes traffic
 * only while it is an Up member. Liveness ignores the cluster, and the application's own readiness
 * group, the opt-out and turning the actor health indicators off all leave readiness alone.
 */
class ReadinessGroupTest {

    @SpringBootApplication
    @EnableActorSupport
    static class TestApp {}

    private static ConfigurableApplicationContext start(String... properties) {
        return new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.actor.provider=cluster",
                        "spring.actor.remote.port=0",
                        "spring.actor.pekko.management.enabled=false",
                        "spring.actor.shutdown.exit-process=false")
                .properties(properties)
                .run();
    }

    private static Status status(ConfigurableApplicationContext context, String path) {
        HealthComponent health = context.getBean(HealthEndpoint.class).healthForPath(path);
        return health == null ? null : health.getStatus();
    }

    @Test
    void readinessWaitsForTheJoinAndDropsWhenTheNodeLeaves() {
        try (ConfigurableApplicationContext context = start("management.endpoint.health.probes.enabled=true")) {
            assertThat(status(context, "readiness")).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(status(context, "liveness")).isEqualTo(Status.UP);
        }
        try (ConfigurableApplicationContext context =
                start("management.endpoint.health.probes.enabled=true", "spring.actor.cluster.join-self=true")) {
            await().atMost(Duration.ofSeconds(20)).until(() -> status(context, "readiness") == Status.UP);
            Cluster cluster = context.getBean(SpringActorSystem.class).unwrap(Cluster.class);

            cluster.manager().tell(Leave.create(cluster.selfMember().address()));

            await().atMost(Duration.ofSeconds(20)).until(() -> status(context, "readiness") != Status.UP);
            assertThat(status(context, "liveness")).isEqualTo(Status.UP);
        }
    }

    @Test
    void theApplicationsOwnReadinessGroupWins() {
        try (ConfigurableApplicationContext context = start(
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState")) {
            assertThat(status(context, "readiness")).isEqualTo(Status.UP);
        }
    }

    @Test
    void theOptOutAndTheDisabledIndicatorsLeaveReadinessAlone() {
        try (ConfigurableApplicationContext context = start(
                "management.endpoint.health.probes.enabled=true", "management.health.actor.readiness.enabled=false")) {
            assertThat(status(context, "readiness")).isEqualTo(Status.UP);
        }
        // Without the indicator, naming it in the group would fail Spring Boot's membership check at startup.
        try (ConfigurableApplicationContext context =
                start("management.endpoint.health.probes.enabled=true", "management.health.actor.enabled=false")) {
            assertThat(status(context, "readiness")).isEqualTo(Status.UP);
        }
    }

    @Test
    void withoutProbesThereIsNoReadinessGroup() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(status(context, "readiness")).isNull();
        }
    }
}
