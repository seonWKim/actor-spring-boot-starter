package io.github.seonwkim.example.shardedchat.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code control.*}: the chat node's jar, the nodes per color (blue is nodes 1..n, green n+1..2n; nodes
 * added later take the next free number after those), the JVM options for each node, and the folder for
 * each node's pid file (their output stays in memory).
 */
@ConfigurationProperties("control")
public record ControlProperties(String nodeJar, int nodesPerColor, String javaOptions, String nodeDir) {

    public static final String BLUE = "blue";
    public static final String GREEN = "green";

    /** Node n serves HTTP on 8080 + n; the control app itself is on 8080. */
    public static int httpPort(int node) {
        return 8080 + node;
    }

    /** Node n listens for its cluster on 2550 + n. */
    public static int remotePort(int node) {
        return 2550 + node;
    }

    /** The most nodes a color runs, its first nodes included. */
    public static final int MAX_PER_COLOR = 6;

    /** The color of one of the first nodes; a node added later keeps the color it was added to. */
    public String colorOf(int node) {
        return node <= nodesPerColor ? BLUE : GREEN;
    }

    /** The node numbers of a color: its cluster's seed nodes. */
    public int[] slots(String color) {
        int first = BLUE.equals(color) ? 1 : nodesPerColor + 1;
        int[] slots = new int[nodesPerColor];
        for (int i = 0; i < nodesPerColor; i++) {
            slots[i] = first + i;
        }
        return slots;
    }

    public static String other(String color) {
        return BLUE.equals(color) ? GREEN : BLUE;
    }
}
