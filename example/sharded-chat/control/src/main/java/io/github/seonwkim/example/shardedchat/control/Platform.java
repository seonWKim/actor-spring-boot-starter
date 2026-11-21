package io.github.seonwkim.example.shardedchat.control;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Where the chat nodes run. On this machine this app runs them itself ({@link LocalPlatform}). Anywhere
 * else (Kubernetes) the platform runs them and its own tools change them, and this app only watches
 * ({@link ExternalPlatform}): it finds the nodes through the chat's own endpoints and shows, for each
 * action, the command that does it there. The deployer, the page and the calls to the nodes are the same.
 */
public interface Platform {

    /** Whether this app starts and stops the nodes; false when the platform runs them. */
    boolean runsNodes();

    /**
     * On a platform that runs the nodes: the command for each action (deploy, activate, retire, addNode,
     * stopNode, startNode), with {@code {color}}, {@code {node}}, {@code {pod}} (the node's name on the
     * platform) and {@code {replicas}} (the color's nodes, plus one for addNode) to fill in. Empty when this
     * app acts.
     */
    Map<String, String> commands();

    /** The node numbers a color starts with: its cluster's first nodes. */
    int[] slots(String color);

    /** The color of a node number. */
    String colorOf(int node);

    /** The number for one more node of the color, given the nodes known now. */
    int nextNumber(String color, List<NodeProcess> known);

    /** Starts node n of the color, running the version; it joins its color's cluster. */
    void start(int node, String color, String version) throws IOException;

    /** Stops node n gracefully: it leaves its cluster and its shards move. False when it does not run. */
    boolean stop(int node);

    /** Stops every node of the color. */
    void retire(String color, List<NodeProcess> live);

    /** Whether node n runs now. */
    boolean alive(int node);

    /**
     * The nodes running that this app did not start (Kubernetes runs them, and they outlive this app),
     * by number: their color and version. Empty on a platform where this app starts every node.
     */
    Map<Integer, Started> discover();

    /** The node's HTTP base URL, as this app reaches it. */
    String baseUrl(int node);

    /** The node's name on the platform: the first part of its host (a pod's name). */
    String nameOf(int node);

    /** The node's cluster port, shown on the page. */
    int remotePort(int node);

    /** The password of the nodes' deployer user, which activates a color. */
    String deployerPassword();

    /** The node's last output lines, oldest first. */
    List<String> log(int node);

    /** A node the platform runs: its color and version. */
    record Started(String color, String version) {}
}
