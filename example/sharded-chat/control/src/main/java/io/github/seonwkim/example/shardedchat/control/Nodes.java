package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The chat nodes this app knows, on whichever {@link Platform} runs them, and the calls to them over
 * HTTP: their generation endpoint, and activation as the deployer.
 */
@Component
public class Nodes {

    static final Duration CALL_TIMEOUT = Duration.ofMillis(800);

    private final Platform platform;
    private final WebClient client;
    private final Map<Integer, NodeProcess> nodes = new ConcurrentHashMap<>();

    public Nodes(Platform platform, WebClient.Builder builder) {
        this.platform = platform;
        this.client = builder.build();
    }

    public Platform platform() {
        return platform;
    }

    /** Starts node n in a color, running that version. */
    public synchronized NodeProcess start(int node, String color, String version) throws IOException {
        platform.start(node, color, version);
        NodeProcess started = new NodeProcess(node, color, version);
        nodes.put(node, started);
        return started;
    }

    /** Stops node n gracefully: it leaves its cluster, and its shards move to the other nodes. */
    public synchronized boolean stop(int node) {
        NodeProcess running = nodes.get(node);
        if (running == null || !platform.stop(node)) {
            return false;
        }
        running.status = "stopping";
        return true;
    }

    /** Stops every node of the color. */
    public synchronized void retire(String color) {
        List<NodeProcess> live = live(color);
        platform.retire(color, live);
        live.forEach(n -> n.status = "stopping");
    }

    /** Adds the nodes the platform runs that this app does not know yet (Kubernetes, after a restart). */
    public synchronized void discover() {
        platform.discover().forEach((node, started) -> {
            NodeProcess known = nodes.get(node);
            if (known == null || !known.color.equals(started.color()) || !known.version.equals(started.version())) {
                nodes.put(node, new NodeProcess(node, started.color(), started.version()));
            }
        });
    }

    public boolean alive(NodeProcess node) {
        return platform.alive(node.node);
    }

    /** The color node n was started in, else the color of its number. */
    public String colorOf(int node) {
        return get(node).map(n -> n.color).orElseGet(() -> platform.colorOf(node));
    }

    /** The number for one more node of the color. */
    public synchronized int nextNumber(String color) {
        return platform.nextNumber(color, all());
    }

    public List<NodeProcess> all() {
        return nodes.values().stream()
                .sorted(Comparator.comparingInt(n -> n.node))
                .toList();
    }

    public Optional<NodeProcess> get(int node) {
        return Optional.ofNullable(nodes.get(node));
    }

    public List<NodeProcess> live(String color) {
        return all().stream().filter(n -> n.color.equals(color) && n.live()).toList();
    }

    /** A node of the color that answers: an up one first, else one that has HTTP. */
    public Optional<NodeProcess> serving(String color) {
        List<NodeProcess> live = live(color);
        return live.stream().filter(NodeProcess::up).findFirst().or(() -> live.stream()
                .filter(n -> "joining".equals(n.status))
                .findFirst());
    }

    public WebClient client() {
        return client;
    }

    /** The node's HTTP base URL. */
    public String base(int node) {
        return platform.baseUrl(node);
    }

    /**
     * GET /actuator/generation: this node's color, the active generation in the store, and the node's own
     * view ({@code node}: what it reads now and whether it is warmed up). Empty while it does not answer.
     */
    Optional<JsonNode> generation(int node) {
        try {
            return client.get()
                    .uri(base(node) + "/actuator/generation")
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class))
                    .timeout(CALL_TIMEOUT)
                    .onErrorResume(e -> Mono.empty())
                    .blockOptional();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * POST /actuator/generation as the deployer: activates the color if the active epoch is still
     * {@code expectedEpoch}. The answer is the endpoint's body, with its HTTP status.
     */
    Mono<Activation> activate(int node, String color, long expectedEpoch, String operationId) {
        return client.post()
                .uri(base(node) + "/actuator/generation")
                .headers(h -> h.setBasicAuth("deployer", platform.deployerPassword()))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(Map.of("color", color, "expectedEpoch", expectedEpoch, "operationId", operationId))
                .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                        .map(body -> new Activation(response.statusCode().value(), body)))
                .timeout(Duration.ofSeconds(5));
    }

    record Activation(int status, JsonNode body) {}
}
