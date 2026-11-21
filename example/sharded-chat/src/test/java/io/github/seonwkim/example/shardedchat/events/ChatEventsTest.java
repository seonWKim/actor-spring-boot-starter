package io.github.seonwkim.example.shardedchat.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatEventsTest {

    // An open event stream would hold the web server's graceful shutdown for its whole timeout.
    @Test
    void openStreamsEndWhenTheApplicationShutsDown() {
        ChatEvents events = new ChatEvents(null, null, 1);
        var received = events.stream().collectList().toFuture();

        events.closeStreams();

        assertThat(received).succeedsWithin(Duration.ofSeconds(1)).isEqualTo(List.of());
    }
}
