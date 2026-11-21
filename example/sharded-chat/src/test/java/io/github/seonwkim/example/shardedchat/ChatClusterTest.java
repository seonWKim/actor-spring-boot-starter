package io.github.seonwkim.example.shardedchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.example.shardedchat.chat.ChatService;
import io.github.seonwkim.example.shardedchat.chat.ChatSimulatorActor;
import io.github.seonwkim.example.shardedchat.events.ChatEvent;
import io.github.seonwkim.example.shardedchat.events.ChatEvents;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The simulation on a one-node cluster: the singleton starts a run, the rooms and users start as
 * sharded entities and report their node, and the page's messages reach them through sharding and come
 * back as events on the topic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "sharded-chat.rooms=2",
            "sharded-chat.users=6",
            "sharded-chat.message-rate=0",
            "sharded-chat.seed=7"
        })
class ChatClusterTest {
    private static final Duration WAIT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void oneNodeCluster(DynamicPropertyRegistry registry) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        registry.add("spring.actor.remote.port", () -> port);
        registry.add("spring.actor.cluster.seed-nodes", () -> "pekko://sharded-chat@127.0.0.1:" + port);
    }

    @Autowired
    ChatService chat;

    @Autowired
    ChatEvents events;

    @Test
    void everyRoomAndUserReportsTheNodeShardingStartedItOn() {
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            ChatSimulatorActor.ChatState state = state();
            assertThat(state.users()).hasSize(6);
            assertThat(state.rooms()).isNotEmpty();
            assertThat(state.rooms()).allSatisfy(r -> assertThat(r.node()).isEqualTo(1));
            assertThat(state.users()).allSatisfy(u -> assertThat(u.node()).isEqualTo(1));
        });
    }

    @Test
    void aBroadcastReachesEveryMemberAndEachDeliveryIsAMessageEvent() {
        List<ChatEvent> seen = new CopyOnWriteArrayList<>();
        events.stream().subscribe(seen::add);
        ChatSimulatorActor.RoomView room = await().atMost(WAIT)
                .ignoreExceptions()
                .until(
                        () -> state().rooms().stream()
                                .filter(r -> !r.members().isEmpty() && r.node() != null)
                                .findFirst()
                                .orElse(null),
                        r -> r != null);

        ChatService.Delivery delivery =
                chat.broadcast(room.id(), "hi all").toCompletableFuture().join();

        assertThat(delivery.replied()).isTrue();
        assertThat(delivery.delivered()).containsExactlyInAnyOrderElementsOf(room.members());
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(seen)
                .filteredOn(e -> ChatEvent.MESSAGE.equals(e.type()) && room.id().equals(e.sender()))
                .extracting(ChatEvent::receiver)
                .containsAll(room.members()));
    }

    private ChatSimulatorActor.ChatState state() {
        return chat.state().toCompletableFuture().join();
    }
}
