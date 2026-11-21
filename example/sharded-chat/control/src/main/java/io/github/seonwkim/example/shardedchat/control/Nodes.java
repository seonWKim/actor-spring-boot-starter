package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Starts and stops the chat nodes as separate JVMs, and talks to them over HTTP. Each node gets its
 * color's seed nodes (so blue and green form two clusters that never meet), its color and the shared
 * generation store, and the deployer password through the environment.
 */
@Component
public class Nodes {

    static final Duration CALL_TIMEOUT = Duration.ofMillis(800);

    private final ControlProperties properties;
    private final GenerationStoreServer store;
    private final WebClient client;
    private final Map<Integer, NodeProcess> nodes = new ConcurrentHashMap<>();
    private final String password = UUID.randomUUID().toString();

    public Nodes(ControlProperties properties, GenerationStoreServer store, WebClient.Builder builder) {
        this.properties = properties;
        this.store = store;
        this.client = builder.build();
    }

    /**
     * Starts node n in a color, running that version. It joins its color's cluster through the color's
     * first nodes, so a node added later joins the same cluster.
     */
    public synchronized NodeProcess start(int node, String color, String version) throws IOException {
        NodeProcess running = nodes.get(node);
        if (running != null && running.process.isAlive()) {
            return running;
        }
        String seeds = Arrays.stream(properties.slots(color))
                .mapToObj(n -> "pekko://sharded-chat@127.0.0.1:" + ControlProperties.remotePort(n))
                .collect(Collectors.joining(","));
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.addAll(Arrays.asList(properties.javaOptions().split("\\s+")));
        command.addAll(List.of(
                "-jar",
                Path.of(properties.nodeJar()).toAbsolutePath().toString(),
                "--sharded-chat.node=" + node,
                "--sharded-chat.version=" + version,
                "--sharded-chat.start-with-nodes=" + properties.nodesPerColor(),
                "--server.port=" + ControlProperties.httpPort(node),
                "--spring.actor.remote.port=" + ControlProperties.remotePort(node),
                "--spring.actor.cluster.seed-nodes=" + seeds,
                "--spring.actor.generation.color=" + color,
                "--spring.actor.generation.store=jdbc",
                // A database behind TCP is not "embedded", so the starter creates its tables only when told to.
                "--spring.actor.jdbc.initialize-schema=always",
                "--spring.datasource.url=" + store.url()));
        Path dir = Path.of(properties.nodeDir());
        Files.createDirectories(dir);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Through the environment, so the password does not show in the process list.
        builder.environment().put("SHARDED_CHAT_DEPLOYER_PASSWORD", password);
        Process process = builder.start();
        Files.writeString(dir.resolve("node-" + node + ".pid"), String.valueOf(process.pid()));
        // The output stays in memory (no log file): the last lines of each run, a restart starts afresh.
        LogBuffer log = new LogBuffer();
        Thread.ofVirtual().name("node-" + node + "-output").start(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(log::add);
            } catch (IOException | UncheckedIOException e) {
                // The process ended.
            }
        });
        NodeProcess started = new NodeProcess(node, color, version, process, log);
        nodes.put(node, started);
        return started;
    }

    /** Stops node n gracefully: it leaves its cluster, and its shards move to the other nodes. */
    public synchronized boolean stop(int node) {
        NodeProcess running = nodes.get(node);
        if (running == null || !running.process.isAlive()) {
            return false;
        }
        running.status = "stopping";
        running.process.destroy();
        return true;
    }

    /** The color node n was started in, else the color of its number. */
    public String colorOf(int node) {
        return get(node).map(n -> n.color).orElseGet(() -> properties.colorOf(node));
    }

    /**
     * The number for one more node of the color: a stopped node of that color after its first nodes, else
     * the next number no node has used.
     */
    public synchronized int nextNumber(String color) {
        int first = properties.nodesPerColor() * 2 + 1;
        return all().stream()
                .filter(n -> n.node >= first && n.color.equals(color) && !n.process.isAlive())
                .mapToInt(n -> n.node)
                .min()
                .orElseGet(() -> Math.max(
                        first, all().stream().mapToInt(n -> n.node + 1).max().orElse(first)));
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

    public static String base(int node) {
        return "http://127.0.0.1:" + ControlProperties.httpPort(node);
    }

    /**
     * GET /actuator/generation: this node's color, the active generation in the store, and the node's own
     * view ({@code node}: what it reads now and whether it is warmed up). Empty while it does not answer.
     */
    Optional<JsonNode> generation(int node) {
        return get(node, "/actuator/generation");
    }

    private Optional<JsonNode> get(int node, String path) {
        try {
            return client.get()
                    .uri(base(node) + path)
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
                .headers(h -> h.setBasicAuth("deployer", password))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(Map.of("color", color, "expectedEpoch", expectedEpoch, "operationId", operationId))
                .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance())
                        .map(body -> new Activation(response.statusCode().value(), body)))
                .timeout(Duration.ofSeconds(5));
    }

    record Activation(int status, JsonNode body) {}

    @PreDestroy
    void stopAll() throws InterruptedException {
        all().forEach(n -> n.process.destroy());
        for (NodeProcess n : all()) {
            if (!n.process.waitFor(20, TimeUnit.SECONDS)) {
                n.process.destroyForcibly();
            }
        }
    }
}
