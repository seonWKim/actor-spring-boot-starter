package io.github.seonwkim.actor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.actor.cluster.ClusterMember;
import io.github.seonwkim.actor.cluster.MemberStatus;
import io.github.seonwkim.actor.test.ActorCluster;
import org.apache.pekko.cluster.typed.Cluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code SpringActorSystem.cluster()} on a two-node cluster: each node's self and the members it sees. */
class ClusterMembersTest {

    /** Not annotated with {@code @Configuration}, so other tests' component scans skip it. */
    @EnableActorSupport
    static class App {}

    private ActorCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void selfIsEachNodeAndMembersListBothUpUntilOneLeaves() {
        cluster = ActorCluster.builder(App.class)
                .nodes(2)
                .systemName("cluster-members")
                .roles(1, "zone-a")
                .roles(2, "zone-b")
                .start();
        SpringActorSystem system1 = system(1);
        SpringActorSystem system2 = system(2);

        ClusterMember self1 = system1.cluster().self();
        ClusterMember self2 = system2.cluster().self();
        assertThat(self1.address()).isEqualTo(system1.nodeAddress());
        assertThat(self1.roles()).contains("zone-a").doesNotContain("zone-b");
        assertThat(self2.address()).isEqualTo(system2.nodeAddress());
        assertThat(self2.roles()).contains("zone-b").doesNotContain("zone-a");
        // Each node sees both, Up, once its own view has caught up.
        for (SpringActorSystem system : new SpringActorSystem[] {system1, system2}) {
            await().atMost(20, SECONDS)
                    .untilAsserted(() -> assertThat(system.cluster().members())
                            .extracting(ClusterMember::address, ClusterMember::status)
                            .containsExactlyInAnyOrder(
                                    tuple(self1.address(), MemberStatus.UP), tuple(self2.address(), MemberStatus.UP)));
        }

        // The leader stays: a follower that leaves is removed at once, a leader only after downing.
        int leaving = isLeader(system1) ? 2 : 1;
        SpringActorSystem staying = leaving == 2 ? system1 : system2;
        String leavingAddress = system(leaving).nodeAddress();
        cluster.stop(leaving);

        await().atMost(20, SECONDS)
                .untilAsserted(() -> assertThat(staying.cluster().members())
                        .extracting(ClusterMember::address)
                        .containsExactly(staying.nodeAddress())
                        .doesNotContain(leavingAddress));
    }

    private SpringActorSystem system(int node) {
        return cluster.node(node).getBean(SpringActorSystem.class);
    }

    private static boolean isLeader(SpringActorSystem system) {
        Cluster cluster = system.unwrap(Cluster.class);
        return cluster.selfMember().address().equals(cluster.state().getLeader());
    }
}
