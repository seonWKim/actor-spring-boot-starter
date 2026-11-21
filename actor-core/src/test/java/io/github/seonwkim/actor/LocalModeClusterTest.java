package io.github.seonwkim.actor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.cluster.ClusterMember;
import io.github.seonwkim.actor.cluster.MemberStatus;
import io.github.seonwkim.actor.internal.PekkoActorSystem;
import io.github.seonwkim.actor.internal.RootGuardian;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code SpringActorSystem.cluster()} outside cluster mode. */
class LocalModeClusterTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void localSystemReportsItselfAsTheOnlyMemberUp() {
        SpringActorSystem system =
                new PekkoActorSystem((ActorSystem<RootGuardian.Command>) (ActorSystem) testKit.system());

        ClusterMember self = system.cluster().self();

        assertThat(self.address()).isEqualTo(system.nodeAddress());
        assertThat(self.status()).isEqualTo(MemberStatus.UP);
        assertThat(self.roles()).isEmpty();
        assertThat(system.cluster().members()).containsExactly(self);
    }
}
