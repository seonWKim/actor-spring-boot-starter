package io.github.seonwkim.example.shardedchat.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sharded-chat.*}: the settings the simulation starts with, an optional seed that makes a run
 * repeat (rooms, users, joins, posts), and how many nodes to wait for before the run starts ({@code
 * start-with-nodes}, default 1), so that its rooms and users spread over all of them from the start.
 * A missing key takes its default.
 */
@ConfigurationProperties("sharded-chat")
public record ChatProperties(
        Integer rooms, Integer users, Double messageRate, Double timeoutShare, Long seed, Integer startWithNodes) {
    public int nodesToWaitFor() {
        return startWithNodes == null ? 1 : startWithNodes;
    }

    public ChatSettings settings() {
        return new ChatSettings(
                rooms == null ? 4 : rooms,
                users == null ? 30 : users,
                messageRate == null ? 2 : messageRate,
                timeoutShare == null ? 0.1 : timeoutShare);
    }
}
