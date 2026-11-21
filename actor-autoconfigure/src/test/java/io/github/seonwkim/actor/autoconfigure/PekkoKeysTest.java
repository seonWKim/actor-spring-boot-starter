package io.github.seonwkim.actor.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The typed {@code spring.actor.*} settings write Pekko keys by name. A key Pekko renames or drops in an upgrade
 * would be ignored silently, so every Pekko key they write must exist in Pekko's reference configuration (and
 * in actor-cluster's, for the starter's own adapters under {@code pekko.discovery}). A lease setting's value is a
 * config path too, and must exist.
 */
class PekkoKeysTest {

    private static final Config REFERENCE = ConfigFactory.defaultReference(PekkoKeysTest.class.getClassLoader());

    /**
     * Keys under {@code pekko} that the starter reads itself, not Pekko: the system's name
     * (DefaultSpringActorSystemBuilder) and the peer-registry adapter's timings (PeerRegistryServiceDiscovery).
     */
    private static final List<String> STARTER_KEYS = List.of(
            "pekko.name",
            "pekko.discovery.spring-actor-peer-registry.heartbeat-interval",
            "pekko.discovery.spring-actor-peer-registry.ttl");

    static Stream<Arguments> everyBranchOfTheTypedSettings() {
        return Stream.of(
                Arguments.of(
                        "seed nodes, leases, roles, remote, delivery, virtual threads",
                        Map.ofEntries(
                                Map.entry("spring.actor.name", "orders"),
                                Map.entry("spring.actor.provider", "cluster"),
                                Map.entry("spring.actor.remote.hostname", "10.0.0.7"),
                                Map.entry("spring.actor.remote.port", "25521"),
                                Map.entry("spring.actor.remote.bind-hostname", "0.0.0.0"),
                                Map.entry("spring.actor.remote.bind-port", "25600"),
                                Map.entry("spring.actor.cluster.seed-nodes", "pekko://orders@10.0.0.7:25521"),
                                Map.entry("spring.actor.cluster.roles", "api"),
                                Map.entry("spring.actor.cluster.singleton.role", "api"),
                                Map.entry("spring.actor.cluster.singleton.use-lease", "true"),
                                Map.entry("spring.actor.cluster.sharding.use-lease", "true"),
                                Map.entry("spring.actor.cluster.downing.strategy", "lease-majority"),
                                Map.entry("spring.actor.cluster.lease.store", "jdbc"),
                                Map.entry("spring.actor.delivery.idempotency-ttl", "90s"),
                                Map.entry("spring.actor.delivery.max-remembered-keys", "500"),
                                Map.entry("spring.actor.virtual-threads.enabled", "true"))),
                Arguments.of(
                        "static quorum, joining itself",
                        Map.of(
                                "spring.actor.provider", "cluster",
                                "spring.actor.cluster.join-self", "true",
                                "spring.actor.cluster.downing.strategy", "static-quorum",
                                "spring.actor.cluster.downing.quorum-size", "2")),
                Arguments.of(
                        "discovery through a store",
                        Map.of(
                                "spring.actor.provider", "cluster",
                                "spring.actor.cluster.join", "discovery",
                                "spring.actor.cluster.discovery.method", "jdbc",
                                "spring.actor.cluster.discovery.service-name", "orders")),
                Arguments.of(
                        "discovery through Kubernetes",
                        Map.of(
                                "spring.actor.provider", "cluster",
                                "spring.actor.cluster.join", "discovery",
                                "spring.actor.cluster.discovery.method", "kubernetes-api")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyBranchOfTheTypedSettings")
    void everyPekkoKeyATypedSettingWritesExists(String branch, Map<String, String> properties) {
        ActorProperties actor = new Binder(new MapConfigurationPropertySource(properties))
                .bind("spring.actor", ActorProperties.class)
                .get();

        List<String> pekkoKeys = new ArrayList<>();
        collectLeaves("", actor.toPekkoConfig(Map.of(), false), pekkoKeys);

        assertThat(pekkoKeys).as("keys written").isNotEmpty();
        assertThat(pekkoKeys)
                .as("Pekko keys missing from the reference configuration")
                .filteredOn(key -> key.startsWith("pekko.") && !STARTER_KEYS.contains(key))
                .filteredOn(key -> !REFERENCE.hasPathOrNull(key))
                .isEmpty();

        // The discovery method names a config with the implementation's class. kubernetes-api's comes with
        // pekko-discovery-kubernetes-api, which the application adds itself.
        Object method = lookup(actor.toPekkoConfig(Map.of(), false), "pekko.discovery.method");
        if (method != null && !method.equals("kubernetes-api")) {
            assertThat(REFERENCE.hasPath("pekko.discovery." + method + ".class"))
                    .as("pekko.discovery.method = %s names a discovery config", method)
                    .isTrue();
        }
        for (String useLease : List.of(
                "pekko.cluster.singleton.use-lease",
                "pekko.cluster.sharding.use-lease",
                "pekko.cluster.split-brain-resolver.lease-majority.lease-implementation")) {
            Object leaseConfig = lookup(actor.toPekkoConfig(Map.of(), false), useLease);
            if (leaseConfig != null) {
                assertThat(REFERENCE.hasPath(leaseConfig + ".lease-class"))
                        .as("%s = %s names a lease config", useLease, leaseConfig)
                        .isTrue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectLeaves(String prefix, Map<String, Object> map, List<String> into) {
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                collectLeaves(path, (Map<String, Object>) child, into);
            } else {
                into.add(path);
            }
        });
    }

    private static Object lookup(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }
}
