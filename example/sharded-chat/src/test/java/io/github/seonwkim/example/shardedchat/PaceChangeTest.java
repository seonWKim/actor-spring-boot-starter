package io.github.seonwkim.example.shardedchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seonwkim.example.shardedchat.chat.ChatService;
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
 * Changing the settings does not pause the chat: every user gets the new pace at once, and a user that
 * then waited at least half a pace before its next post would leave the whole chat silent that long.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"sharded-chat.rooms=2", "sharded-chat.users=20", "sharded-chat.message-rate=10", "sharded-chat.seed=7"
        })
class PaceChangeTest {

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
    void usersKeepPostingRightAfterThePaceChanges() {
        List<Long> posts = new CopyOnWriteArrayList<>();
        events.stream()
                .filter(e -> ChatEvent.MESSAGE.equals(e.type()) && "Post".equals(e.messageType()))
                .subscribe(e -> posts.add(System.nanoTime()));
        // All 20 users are in a room and post at their pace (10 a second together: 2 s each).
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> {
            var users = chat.state().toCompletableFuture().join().users();
            return users.size() == 20 && users.stream().noneMatch(u -> u.rooms().isEmpty());
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> posts.size() >= 20);

        chat.update(null, null, 5.0).toCompletableFuture().join();
        long changed = System.nanoTime();

        // At the new pace (4 s each) the 20 users post 5 times a second. Had each waited at least half the
        // pace, none would post for 2 s.
        await().atMost(Duration.ofMillis(1500))
                .until(() -> posts.stream().anyMatch(at -> at > changed));
        assertThat(posts).anyMatch(at -> at > changed);
    }
}
