package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Nodes that a platform runs (Kubernetes: k8s/), watched from outside. This app does not start, stop or
 * scale them, and needs no access to the platform: it asks each color's cluster for its members
 * ({@code GET /api/cluster/nodes} through {@code control.external.color-url}) and reaches each node at the
 * host the cluster knows it by. Each action is a command for the platform's own tools, from
 * {@code control.external.commands}, which the page shows instead of doing it.
 */
@Component
@ConditionalOnProperty(name = "control.platform", havingValue = "external")
public class ExternalPlatform implements Platform {

    private static final Duration TIMEOUT = Duration.ofMillis(800);

    private final Settings settings;
    private final WebClient client;
    /** How many discoveries in a row a color may go unanswered before its nodes count as gone. */
    static final int MISSES_KEPT = 5;

    private volatile Map<Integer, Member> members = Map.of();
    private final Map<String, Integer> misses = new HashMap<>();

    /**
     * {@code control.external.*}: the URL of a color's chat Service ({@code {color}} filled in), the HTTP
     * port of every node, and the command for each action.
     */
    @ConfigurationProperties("control.external")
    public record Settings(
            String colorUrl,
            int nodeHttpPort,
            String instanceName,
            Map<String, Integer> firstNode,
            Map<String, String> commands) {
        public Settings {
            colorUrl = colorUrl == null ? "http://chat-{color}:8080" : colorUrl;
            nodeHttpPort = nodeHttpPort == 0 ? 8080 : nodeHttpPort;
            instanceName = instanceName == null ? "chat-{color}-{index}" : instanceName;
            firstNode = firstNode == null ? Map.of() : Map.copyOf(firstNode);
            commands = commands == null ? Map.of() : Map.copyOf(commands);
        }

        /**
         * The platform's name for a node (on Kubernetes, its pod): the color's instances count from 0 at
         * the color's first node number, the same rule the platform uses to number them.
         */
        String instanceOf(String color, int node) {
            int index = node - firstNode.getOrDefault(color, 1);
            return instanceName.replace("{color}", color).replace("{index}", Integer.toString(index));
        }
    }

    /** One member of a color's cluster, as its nodes report it. */
    record Member(String color, String host, int port, String version, String status) {}

    public ExternalPlatform(Settings settings, WebClient.Builder builder) {
        this.settings = settings;
        this.client = builder.build();
    }

    /** The members a color's {@code GET /api/cluster/nodes} names, by node number; removed ones left out. */
    static Map<Integer, Member> membersOf(String color, JsonNode reply) {
        Map<Integer, Member> out = new HashMap<>();
        for (JsonNode n : reply.path("nodes")) {
            String status = n.path("status").asText();
            if (!"removed".equals(status)) {
                out.put(
                        n.path("node").asInt(),
                        new Member(
                                color,
                                n.path("host").asText(),
                                n.path("port").asInt(),
                                n.path("version").asText(""),
                                status));
            }
        }
        return out;
    }

    @Override
    public boolean runsNodes() {
        return false;
    }

    @Override
    public Map<String, String> commands() {
        return settings.commands();
    }

    /**
     * Each color's members: through its Service, else through a node of it this app already knows (the
     * Service's name can lead to a pod that is still starting), else as last seen for a few rounds, so
     * one failed call does not make a whole color vanish from the page, but a retired color does.
     */
    @Override
    public Map<Integer, Started> discover() {
        Map<Integer, Member> before = members;
        Map<Integer, Member> found = new HashMap<>();
        for (String color : List.of(ControlProperties.BLUE, ControlProperties.GREEN)) {
            List<String> urls = new ArrayList<>();
            urls.add(settings.colorUrl().replace("{color}", color));
            before.values().stream()
                    .filter(m -> m.color().equals(color) && "up".equals(m.status()))
                    .forEach(m -> urls.add("http://" + m.host() + ":" + settings.nodeHttpPort()));
            Optional<JsonNode> reply =
                    urls.stream().map(this::nodesAt).flatMap(Optional::stream).findFirst();
            int missed = reply.isPresent() ? 0 : misses.getOrDefault(color, 0) + 1;
            misses.put(color, missed);
            found.putAll(reply.map(r -> membersOf(color, r)).orElseGet(() -> lastSeen(before, color, missed)));
        }
        members = found;
        Map<Integer, Started> out = new HashMap<>();
        found.forEach((node, m) -> out.put(node, new Started(m.color(), m.version())));
        return out;
    }

    private Optional<JsonNode> nodesAt(String url) {
        return client.get()
                .uri(url + "/api/cluster/nodes")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .onErrorResume(e -> Mono.empty())
                .blockOptional();
    }

    /** The color's members as last seen, until it has gone unanswered more than {@link #MISSES_KEPT} times. */
    static Map<Integer, Member> lastSeen(Map<Integer, Member> before, String color, int missed) {
        Map<Integer, Member> out = new HashMap<>();
        if (missed > MISSES_KEPT) {
            return out;
        }
        before.forEach((node, m) -> {
            if (m.color().equals(color)) {
                out.put(node, m);
            }
        });
        return out;
    }

    @Override
    public boolean alive(int node) {
        return members.containsKey(node);
    }

    @Override
    public String colorOf(int node) {
        Member m = members.get(node);
        return m == null ? ControlProperties.BLUE : m.color();
    }

    @Override
    public String baseUrl(int node) {
        Member m = members.get(node);
        return "http://" + (m == null ? "unknown" : m.host()) + ":" + settings.nodeHttpPort();
    }

    @Override
    public String nameOf(int node) {
        Member m = members.get(node);
        return m == null ? "node-" + node : settings.instanceOf(m.color(), node);
    }

    @Override
    public int remotePort(int node) {
        Member m = members.get(node);
        return m == null ? 0 : m.port();
    }

    @Override
    public List<String> log(int node) {
        Member m = members.get(node);
        return List.of("node " + node + " runs on the platform" + (m == null ? "" : " as " + m.host())
                + ": read its output with the platform's tools (on Kubernetes: kubectl logs)");
    }

    // The platform runs the nodes: these are its tools' job (commands()).

    @Override
    public int[] slots(String color) {
        throw notHere("deploy");
    }

    @Override
    public int nextNumber(String color, List<NodeProcess> known) {
        throw notHere("addNode");
    }

    @Override
    public void start(int node, String color, String version) {
        throw notHere("startNode");
    }

    @Override
    public boolean stop(int node) {
        throw notHere("stopNode");
    }

    @Override
    public void retire(String color, List<NodeProcess> live) {
        throw notHere("retire");
    }

    @Override
    public String deployerPassword() {
        throw notHere("activate");
    }

    private UnsupportedOperationException notHere(String action) {
        return new UnsupportedOperationException(
                "the platform runs the nodes: " + settings.commands().getOrDefault(action, action));
    }
}
