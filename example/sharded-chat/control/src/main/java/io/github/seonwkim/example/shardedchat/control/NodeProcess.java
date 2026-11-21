package io.github.seonwkim.example.shardedchat.control;

/**
 * A chat node of the platform: its number, color and version, and what the last
 * check found. Status: {@code starting} (no HTTP yet), {@code joining} (not warmed up), {@code up}
 * (warmed up: an Up member of a cluster with enough Up members), {@code stopping}, {@code stopped}.
 */
public final class NodeProcess {
    final int node;
    final String color;
    final String version;

    volatile String status = "starting";
    /** The active generation's color and epoch as this node reads them, null before the first read. */
    volatile String sees;

    volatile long seesEpoch;

    NodeProcess(int node, String color, String version) {
        this.node = node;
        this.color = color;
        this.version = version;
    }

    boolean live() {
        return !"stopped".equals(status);
    }

    boolean up() {
        return "up".equals(status);
    }
}
