package io.github.seonwkim.example.shardedchat.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.seonwkim.actor.serialization.JsonSerializable;
import java.time.Instant;

/**
 * One thing that happened, as the page receives it on the event stream ({@code type} is the SSE event
 * name):
 *
 * <ul>
 *   <li>{@code message}: one real send from {@code sender} to {@code receiver}, with the message's type
 *       and payload. {@code node} is the sender's node.
 *   <li>{@code log}: a chat log line of {@code actorId}: its {@code kind} (joined, left, sent,
 *       received, timeout, room_created, room_stopped), {@code level} and text ({@code message}).
 *   <li>{@code member}: node {@code node} (actorId {@code node-<n>}) changed its cluster membership to
 *       {@code message}: joining, up, leaving, unreachable or removed.
 * </ul>
 *
 * <p>Message and log events travel to every node over a distributed topic, so it is {@link
 * JsonSerializable}. Fields that are not set are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
        String type,
        String actorId,
        String message,
        String timestamp,
        Integer node,
        String kind,
        String level,
        String sender,
        String receiver,
        @JsonProperty("message_type") String messageType,
        String payload)
        implements JsonSerializable {
    public static final String MESSAGE = "message";
    public static final String LOG = "log";
    public static final String MEMBER = "member";
    public static final String INFO = "info";
    public static final String WARN = "warn";

    public static ChatEvent message(String sender, String receiver, Object message, int node) {
        String payload = MessagePayload.of(message);
        return new ChatEvent(
                MESSAGE,
                sender,
                payload,
                Instant.now().toString(),
                node,
                null,
                null,
                sender,
                receiver,
                MessagePayload.typeOf(message),
                payload);
    }

    public static ChatEvent log(String actorId, String level, String kind, String text, Integer node) {
        return new ChatEvent(LOG, actorId, text, Instant.now().toString(), node, kind, level, null, null, null, null);
    }

    public static ChatEvent member(int node, String status) {
        return new ChatEvent(
                MEMBER, "node-" + node, status, Instant.now().toString(), node, null, null, null, null, null, null);
    }
}
