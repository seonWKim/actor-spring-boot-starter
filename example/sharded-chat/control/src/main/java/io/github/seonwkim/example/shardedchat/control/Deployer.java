package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The blue-green pipeline, the four stages of the starter's deployment guide: deploy the standby color
 * and wait for it to warm up ({@code /actuator/health/warmup}), activate it ({@code POST
 * /actuator/generation}, a compare-and-set on the epoch), verify that every node reads the new
 * generation, and retire the old color. Until the retire, activating the old color again rolls back.
 *
 * <p>Once a second it checks every node and reports what changed. At start it deploys blue and
 * activates it, so the chat runs.
 */
@Component
public class Deployer {

    /** The warm-up group's own threshold (management.health.actor.warmup.min-members on the nodes). */
    static final int MIN_WARM = 2;

    private final Nodes nodes;
    private final ControlProperties properties;
    private final ControlEvents events;
    private final EventRelay relay;

    private final Map<String, String> versions = new HashMap<>();
    private int releases;

    @Nullable private volatile Active active;

    @Nullable private volatile Active verifying;

    private volatile boolean busy;
    private boolean initial = true;
    private final Map<String, Boolean> warmReported = new HashMap<>();

    /** The active generation: its color and epoch, and the operation that activated it. */
    public record Active(String color, long epoch, String operationId, String activatedAt) {}

    public Deployer(Nodes nodes, ControlProperties properties, ControlEvents events, EventRelay relay) {
        this.nodes = nodes;
        this.properties = properties;
        this.events = events;
        this.relay = relay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        deploy(ControlProperties.BLUE);
    }

    @Nullable public Active active() {
        return active;
    }

    public boolean busy() {
        return busy || verifying != null;
    }

    public Optional<String> version(String color) {
        return Optional.ofNullable(versions.get(color));
    }

    public Pipeline pipeline() {
        Active current = active;
        return Pipeline.of(views(), current == null ? null : current.color(), MIN_WARM);
    }

    /** Stage 1: starts the color's nodes with the next version; they form their own cluster. */
    public synchronized void deploy(String color) throws IOException {
        String version = "v" + ++releases;
        versions.put(color, version);
        warmReported.put(color, false);
        events.deploy("info", "deploy " + color + " " + version + ": starting nodes " + slots(color));
        for (int node : properties.slots(color)) {
            nodes.start(node, color, version);
            events.member(node, "starting");
        }
    }

    /**
     * One more node for a running color, with its version: it joins the color's cluster, and the shard
     * coordinators can place new shards on it. Empty when the color does not run or has the most nodes.
     */
    public synchronized Optional<Integer> addNode(String color) throws IOException {
        String version = versions.get(color);
        int live = nodes.live(color).size();
        if (version == null || live == 0 || live >= ControlProperties.MAX_PER_COLOR) {
            return Optional.empty();
        }
        int node = nodes.nextNumber(color);
        nodes.start(node, color, version);
        events.member(node, "starting");
        events.deploy("info", "adding node " + node + " to " + color + " " + version);
        return Optional.of(node);
    }

    /**
     * Stage 2: activates the color through one of its nodes. The expected epoch is the one active now,
     * and the operation ID names this activation, so a retry is answered with the same generation.
     */
    public synchronized String activate(String color) {
        Optional<NodeProcess> node = nodes.serving(color).filter(NodeProcess::up);
        if (node.isEmpty()) {
            return "no " + color + " node is warmed up";
        }
        Active current = active;
        long expected = current == null ? 0 : current.epoch();
        String operationId = "activate-" + color + "-" + versions.get(color) + "-after-epoch-" + expected;
        busy = true;
        try {
            Nodes.Activation result =
                    nodes.activate(node.get().node, color, expected, operationId).block(Duration.ofSeconds(6));
            String outcome = result == null ? "no answer" : result.body().path("outcome").asText("HTTP " + result.status());
            JsonNode generation = result == null ? null : result.body().path("generation");
            if (result != null && result.status() == 200 && generation != null && generation.isObject()) {
                Active next = toActive(generation);
                active = next;
                verifying = next;
                events.deploy(
                        "info",
                        "activate " + color + ": " + outcome + ", epoch " + next.epoch() + " (operation " + operationId
                                + ", expected epoch " + expected + "); verifying");
                return color + " is active at epoch " + next.epoch() + " (" + outcome + ")";
            }
            events.deploy("error", "activate " + color + " refused: " + outcome + " (expected epoch " + expected + ")");
            return outcome;
        } catch (RuntimeException e) {
            events.deploy("error", "activate " + color + " failed: " + e.getMessage());
            return "failed";
        } finally {
            busy = false;
        }
    }

    /** Stage 4: stops the color's nodes. */
    public synchronized void retire(String color) {
        events.deploy("info", "retire " + color + " " + versions.get(color) + ": stopping nodes " + slots(color));
        for (NodeProcess node : nodes.live(color)) {
            nodes.stop(node.node);
        }
    }

    public boolean stop(int node) {
        boolean stopped = nodes.stop(node);
        if (stopped) {
            events.deploy("info", "stopping node " + node);
        }
        return stopped;
    }

    public synchronized boolean startNode(int node) throws IOException {
        String color = nodes.colorOf(node);
        String version = versions.get(color);
        if (version == null || nodes.get(node).filter(n -> n.process.isAlive()).isPresent()) {
            return false;
        }
        nodes.start(node, color, version);
        events.member(node, "starting");
        events.deploy("info", "starting node " + node + " (" + color + " " + version + ")");
        return true;
    }

    /** Checks every node, follows the active generation, and moves the pipeline on. */
    @Scheduled(fixedDelay = 1000)
    public void check() {
        Active seen = null;
        for (NodeProcess node : nodes.all()) {
            String before = node.status;
            // One read of the node's generation endpoint: the store's active generation, and this node's
            // view (what it reads now, and whether it is warmed up).
            JsonNode generation = node.process.isAlive() && !"stopping".equals(node.status)
                    ? nodes.generation(node.node).orElse(null)
                    : null;
            if (!node.process.isAlive()) {
                node.status = "stopped";
            } else if (!"stopping".equals(node.status)) {
                node.status = generation == null
                        ? "starting"
                        : generation.path("node").path("warm").asBoolean(false) ? "up" : "joining";
            }
            if (generation != null) {
                JsonNode reads = generation.path("node").path("active");
                node.sees = reads.isObject() ? reads.path("color").asText() : null;
                node.seesEpoch = reads.isObject() ? reads.path("epoch").asLong() : 0;
                JsonNode store = generation.path("active");
                if (store.isObject() && (seen == null || store.path("epoch").asLong() > seen.epoch())) {
                    seen = toActive(store);
                }
            }
            if (!node.status.equals(before)) {
                events.member(node.node, node.status.equals("stopped") ? "removed" : node.status);
            }
        }
        if (seen != null && (active == null || seen.epoch() >= active.epoch())) {
            active = seen;
        }
        for (String color : List.of(ControlProperties.BLUE, ControlProperties.GREEN)) {
            relay.check(color);
            reportWarm(color);
        }
        if (initial && active == null && warm(ControlProperties.BLUE)) {
            initial = false;
            activate(ControlProperties.BLUE);
        }
        verify();
    }

    // Stage 3: every running node reads the new generation (node.active in each node's generation
    // endpoint; a node reuses its read of the store for up to a second).
    private void verify() {
        Active target = verifying;
        if (target == null) {
            return;
        }
        List<NodeProcess> up = nodes.all().stream().filter(NodeProcess::up).toList();
        if (up.stream().allMatch(n -> target.color().equals(n.sees) && n.seesEpoch == target.epoch())) {
            verifying = null;
            String other = ControlProperties.other(target.color());
            events.deploy(
                    "info",
                    "verified: all " + up.size() + " running nodes read " + target.color() + " at epoch "
                            + target.epoch() + "; " + target.color() + " chats"
                            + (nodes.live(other).isEmpty()
                                    ? ""
                                    : ", " + other + " is paused (roll back to it, or retire it)"));
        }
    }

    private void reportWarm(String color) {
        List<NodeProcess> live = nodes.live(color);
        boolean warm = warm(color);
        if (warm && !warmReported.getOrDefault(color, true)) {
            warmReported.put(color, true);
            Active current = active;
            boolean standby = current != null && !current.color().equals(color);
            events.deploy(
                    "info",
                    color + " " + versions.get(color) + ": " + live.size() + " of " + live.size()
                            + " nodes warmed up" + (standby ? "; ready to activate" : ""));
        }
    }

    private boolean warm(String color) {
        List<NodeProcess> live = nodes.live(color);
        return live.size() >= MIN_WARM && live.stream().allMatch(NodeProcess::up);
    }

    private List<Pipeline.Node> views() {
        return nodes.all().stream()
                .map(n -> new Pipeline.Node(n.color, n.live(), n.up()))
                .toList();
    }

    private String slots(String color) {
        int[] slots = properties.slots(color);
        return slots[0] + " to " + slots[slots.length - 1];
    }

    private static Active toActive(JsonNode g) {
        return new Active(
                g.path("color").asText(),
                g.path("epoch").asLong(),
                g.path("operationId").asText(),
                g.path("activatedAt").asText());
    }
}
