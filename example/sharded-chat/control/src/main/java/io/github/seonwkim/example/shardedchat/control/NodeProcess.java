package io.github.seonwkim.example.shardedchat.control;

/**
 * A chat node the control app started: its number, color, version and process, and what the last
 * check found. Status: {@code starting} (no HTTP yet), {@code joining} (not warmed up), {@code up}
 * (warmed up: an Up member of a cluster with enough Up members), {@code stopping}, {@code stopped}.
 */
public final class NodeProcess {
    final int node;
    final String color;
    final String version;
    final Process process;
    /** The node's output, in memory: its last {@link LogBuffer#LINES} lines. */
    final LogBuffer log;
    volatile String status = "starting";
    /** The active generation's color and epoch as this node reads them, null before the first read. */
    volatile String sees;

    volatile long seesEpoch;

    NodeProcess(int node, String color, String version, Process process, LogBuffer log) {
        this.node = node;
        this.color = color;
        this.version = version;
        this.process = process;
        this.log = log;
    }

    boolean live() {
        return !"stopped".equals(status);
    }

    boolean up() {
        return "up".equals(status);
    }
}
