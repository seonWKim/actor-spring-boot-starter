package io.github.seonwkim.example.shardedchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A chat simulation on a cluster: chat rooms and users are sharded actors spread over the nodes, a
 * cluster singleton runs the simulation, and every node streams what happens to the page at {@code /}.
 * Start three nodes with {@code bin/start} (README.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ShardedChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShardedChatApplication.class, args);
    }
}
