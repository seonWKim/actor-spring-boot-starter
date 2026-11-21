package io.github.seonwkim.example.shardedchat.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void everyChatIdInAnEventGetsItsColorOnceAndOtherTextIsLeftAlone() throws Exception {
        JsonNode event = new ObjectMapper()
                .readTree(
                        """
                {"actorId":"chat-user-12","message":"sent \\"hi\\" to chat-room-1","receiver":"chat-room-1@blue",
                 "payload":{"members":["chat-user-1","chat-user-12"]},"timestamp":"2026-11-21T10:00:00Z","node":2}
                """);

        Ids.qualify(event, "green");

        assertThat(event.path("actorId").asText()).isEqualTo("chat-user-12@green");
        assertThat(event.path("message").asText()).isEqualTo("sent \"hi\" to chat-room-1@green");
        assertThat(event.path("receiver").asText()).isEqualTo("chat-room-1@blue");
        assertThat(event.path("payload").path("members").get(1).asText()).isEqualTo("chat-user-12@green");
        assertThat(event.path("timestamp").asText()).isEqualTo("2026-11-21T10:00:00Z");
    }

    @Test
    void aRequestForAnIdWithAColorGoesToThatColorWithTheBareId() {
        assertThat(Ids.colorOf("chat-user-7@green")).contains("green");
        assertThat(Ids.bare("chat-user-7@green")).isEqualTo("chat-user-7");
        assertThat(Ids.colorOf("chat-user-7")).isEmpty();
    }
}
