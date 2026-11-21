package io.github.seonwkim.example.shardedchat.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExternalPlatformTest {

    @Test
    void aColorsMembersAreItsNodesWithTheirHostAndVersionAndRemovedOnesAreLeftOut() throws Exception {
        var reply = new ObjectMapper()
                .readTree(
                        """
                {"nodes": [
                  {"node": 1, "host": "chat-blue-0.chat-blue", "port": 25520, "version": "v2", "status": "up"},
                  {"node": 2, "host": "chat-blue-1.chat-blue", "port": 25520, "version": "v2", "status": "removed"}
                ]}""");

        var members = ExternalPlatform.membersOf("blue", reply);

        assertThat(members).containsOnlyKeys(1);
        assertThat(members.get(1))
                .isEqualTo(new ExternalPlatform.Member("blue", "chat-blue-0.chat-blue", 25520, "v2", "up"));
    }

    // The page's stop command names the pod; a wrong index deletes another node's pod.
    @Test
    void aNodesInstanceIsCountedFromItsColorsFirstNode() {
        var settings = new ExternalPlatform.Settings(
                null, 0, "chat-{color}-{index}", java.util.Map.of("blue", 1, "green", 51), null);

        assertThat(settings.instanceOf("blue", 2)).isEqualTo("chat-blue-1");
        assertThat(settings.instanceOf("green", 51)).isEqualTo("chat-green-0");
    }

    // A retired color answers nobody; kept forever, the page would show its nodes as starting.
    @Test
    void aColorThatStopsAnsweringIsKeptForAFewRoundsThenDropped() {
        var green = new ExternalPlatform.Member("green", "10.1.0.9", 25520, "v2", "up");
        var blue = new ExternalPlatform.Member("blue", "10.1.0.8", 25520, "v1", "up");
        var before = java.util.Map.of(51, green, 1, blue);

        assertThat(ExternalPlatform.lastSeen(before, "green", 1)).containsOnlyKeys(51);
        assertThat(ExternalPlatform.lastSeen(before, "green", ExternalPlatform.MISSES_KEPT + 1))
                .isEmpty();
    }
}
