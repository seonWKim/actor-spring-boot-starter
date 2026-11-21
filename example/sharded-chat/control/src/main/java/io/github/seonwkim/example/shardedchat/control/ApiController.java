package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The page's API. Chat requests go to the active color, like a load balancer that follows the active
 * generation; a request that names an id with a color ({@code chat-user-7@green}) goes to that color,
 * so the standby can be tried before it is activated. Reads merge both colors.
 */
@RestController
public class ApiController {

    private final Nodes nodes;
    private final Deployer deployer;
    private final ControlEvents events;
    private final ObjectMapper json;
    private final Map<Integer, Integer> actorsByNode = new HashMap<>();

    public ApiController(Nodes nodes, Deployer deployer, ControlEvents events, ObjectMapper json) {
        this.nodes = nodes;
        this.deployer = deployer;
        this.events = events;
        this.json = json;
    }

    // ---- chat ----

    /** Both colors' runs: rooms and users with their color and node; the settings are the active color's. */
    @GetMapping("/api/chat/state")
    public Mono<JsonNode> state() {
        List<String> colors = List.of(ControlProperties.BLUE, ControlProperties.GREEN);
        return Flux.fromIterable(colors)
                .flatMap(color -> nodes.serving(color)
                        .map(node -> fetch(node.node, "/api/chat/state")
                                .map(state -> Map.entry(color, Ids.qualify(state, color))))
                        .orElse(Mono.empty()))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(this::merge);
    }

    private JsonNode merge(Map<String, JsonNode> byColor) {
        Deployer.Active active = deployer.active();
        ObjectNode out = json.createObjectNode();
        ArrayNode rooms = out.putArray("rooms");
        ArrayNode users = out.putArray("users");
        ObjectNode runs = out.putObject("colors");
        Map<Integer, Integer> actors = new HashMap<>();
        byColor.forEach((color, state) -> {
            for (String key : List.of("rooms", "users")) {
                for (JsonNode entry : state.path(key)) {
                    ObjectNode copy = ((ObjectNode) entry).put("color", color);
                    (key.equals("rooms") ? rooms : users).add(copy);
                    if (entry.path("node").isInt()) {
                        actors.merge(entry.path("node").asInt(), 1, Integer::sum);
                    }
                }
            }
            runs.putObject(color)
                    .put("running", state.path("running").asBoolean())
                    .set("counts", state.path("counts"));
        });
        JsonNode main = active != null && byColor.containsKey(active.color())
                ? byColor.get(active.color())
                : byColor.values().stream().findFirst().orElse(json.createObjectNode());
        out.set("settings", main.path("settings"));
        out.set("counts", main.path("counts"));
        synchronized (actorsByNode) {
            actorsByNode.clear();
            actorsByNode.putAll(actors);
        }
        return out;
    }

    @PutMapping("/api/chat/{setting:rooms|users|rate}")
    public Mono<ResponseEntity<JsonNode>> set(@PathVariable String setting, @RequestBody JsonNode body) {
        return forward(activeColor(), HttpMethod.PUT, "/api/chat/" + setting, body);
    }

    @PostMapping("/api/chat/rooms")
    public Mono<ResponseEntity<JsonNode>> addRoom(@RequestBody(required = false) JsonNode body) {
        return forward(activeColor(), HttpMethod.POST, "/api/chat/rooms", body);
    }

    @PostMapping("/api/chat/send")
    public Mono<ResponseEntity<JsonNode>> send(@RequestBody ObjectNode body) {
        return toNamed(body, "to", "/api/chat/send");
    }

    @PostMapping("/api/chat/broadcast")
    public Mono<ResponseEntity<JsonNode>> broadcast(@RequestBody ObjectNode body) {
        return toNamed(body, "room", "/api/chat/broadcast");
    }

    // The color the id names, else the active one; the node gets the id without it.
    private Mono<ResponseEntity<JsonNode>> toNamed(ObjectNode body, String field, String path) {
        String id = body.path(field).asText("");
        String color = Ids.colorOf(id).orElseGet(this::activeColor);
        body.put(field, Ids.bare(id));
        return forward(color, HttpMethod.POST, path, body);
    }

    @GetMapping("/api/chat/logs")
    public List<JsonNode> logs() {
        return events.logs();
    }

    @GetMapping(path = "/api/chat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events() {
        return events.stream();
    }

    // ---- nodes ----

    /** Every node the control app started: number, color, version, status, and how many rooms and users. */
    @GetMapping("/api/cluster/nodes")
    public JsonNode clusterNodes() {
        ObjectNode out = json.createObjectNode();
        ArrayNode list = out.putArray("nodes");
        Map<Integer, Integer> actors;
        synchronized (actorsByNode) {
            actors = Map.copyOf(actorsByNode);
        }
        for (NodeProcess n : nodes.all()) {
            list.addObject()
                    .put("node", n.node)
                    .put("port", ControlProperties.remotePort(n.node))
                    .put("http", ControlProperties.httpPort(n.node))
                    .put("status", n.status.equals("stopped") ? "removed" : n.status)
                    .put("color", n.color)
                    .put("version", n.version)
                    .put("actors", n.up() ? actors.getOrDefault(n.node, 0) : 0);
        }
        return out;
    }

    /** One more node for a color ({@code {"color":"green"}}), the active color when none is given. */
    @PostMapping("/api/cluster/nodes")
    public Mono<ResponseEntity<JsonNode>> addNode(@RequestBody(required = false) JsonNode body) {
        String asked = body == null ? "" : body.path("color").asText("");
        String color = asked.isEmpty() ? activeColor() : asked;
        if (!ControlProperties.BLUE.equals(color) && !ControlProperties.GREEN.equals(color)) {
            return Mono.just(refuse("color must be blue or green"));
        }
        return blocking(() -> deployer.addNode(color)
                .map(node -> ok("adding node " + node + " to " + color))
                .orElseGet(() -> refuse(
                        color + " is not running, or already runs " + ControlProperties.MAX_PER_COLOR + " nodes")));
    }

    @PostMapping("/api/cluster/nodes/{node}/stop")
    public ResponseEntity<JsonNode> stopNode(@PathVariable int node) {
        return deployer.stop(node) ? ok("stopping node " + node) : refuse("node " + node + " is not running");
    }

    @PostMapping("/api/cluster/nodes/{node}/start")
    public Mono<ResponseEntity<JsonNode>> startNode(@PathVariable int node) {
        return blocking(() -> deployer.startNode(node)
                ? ok("starting node " + node)
                : refuse("node " + node + " is running, or its color is not deployed"));
    }

    // ---- blue-green ----

    /** The active generation, each color's version and nodes, and what the pipeline can do next. */
    @GetMapping("/api/deploy")
    public JsonNode deployState() {
        Deployer.Active active = deployer.active();
        Pipeline next = deployer.pipeline();
        ObjectNode out = json.createObjectNode();
        if (active != null) {
            out.set("active", json.valueToTree(active));
        } else {
            out.putNull("active");
        }
        ObjectNode colors = out.putObject("colors");
        for (String color : List.of(ControlProperties.BLUE, ControlProperties.GREEN)) {
            List<NodeProcess> live = nodes.live(color);
            ObjectNode c = colors.putObject(color);
            c.put("version", deployer.version(color).orElse(null));
            c.put("nodes", live.size());
            c.put("up", live.stream().filter(NodeProcess::up).count());
            c.put(
                    "role",
                    live.isEmpty() ? "none" : active != null && active.color().equals(color) ? "active" : "standby");
        }
        out.put("busy", deployer.busy());
        ObjectNode actions = out.putObject("next");
        actions.put("deploy", next.deploy());
        actions.put("activate", next.activate());
        actions.put("retire", next.retire());
        return out;
    }

    @PostMapping("/api/deploy/deploy")
    public Mono<ResponseEntity<JsonNode>> deploy() {
        return blocking(() -> {
            String color = deployer.pipeline().deploy();
            if (color == null) {
                return refuse("nothing to deploy: both colors run, or none is active yet");
            }
            deployer.deploy(color);
            return ok("deploying " + color);
        });
    }

    @PostMapping("/api/deploy/activate")
    public Mono<ResponseEntity<JsonNode>> activate() {
        return blocking(() -> {
            String color = deployer.pipeline().activate();
            if (color == null) {
                return refuse("nothing to activate: the standby color is not warmed up");
            }
            return ok(deployer.activate(color));
        });
    }

    @PostMapping("/api/deploy/retire")
    public Mono<ResponseEntity<JsonNode>> retire() {
        return blocking(() -> {
            String color = deployer.pipeline().retire();
            if (color == null) {
                return refuse("nothing to retire: only the active color runs");
            }
            deployer.retire(color);
            return ok("retiring " + color);
        });
    }

    // ---- helpers ----

    private String activeColor() {
        Deployer.Active active = deployer.active();
        return active == null ? ControlProperties.BLUE : active.color();
    }

    private Mono<JsonNode> fetch(int node, String path) {
        return nodes.client()
                .get()
                .uri(Nodes.base(node) + path)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Nodes.CALL_TIMEOUT.multipliedBy(2))
                .onErrorResume(e -> Mono.empty());
    }

    // To a node of the color that answers; its status and body come back, with the ids given their color.
    private Mono<ResponseEntity<JsonNode>> forward(String color, HttpMethod method, String path, JsonNode body) {
        Optional<NodeProcess> node = nodes.serving(color);
        if (node.isEmpty()) {
            return Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "no " + color + " node is up"));
        }
        var request = nodes.client().method(method).uri(Nodes.base(node.get().node) + path);
        var withBody = body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return withBody.exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(json.createObjectNode())
                        .map(reply ->
                                ResponseEntity.status(response.statusCode()).body(Ids.qualify(reply, color))))
                .timeout(java.time.Duration.ofSeconds(6))
                .onErrorResume(e -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, color + " did not answer")));
    }

    private interface Action {
        ResponseEntity<JsonNode> run() throws IOException;
    }

    // The pipeline and the processes block: off the event loop.
    private Mono<ResponseEntity<JsonNode>> blocking(Action action) {
        return Mono.fromCallable(action::run).subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<JsonNode> ok(String result) {
        return ResponseEntity.ok(json.createObjectNode().put("result", result));
    }

    private ResponseEntity<JsonNode> refuse(String reason) {
        return error(HttpStatus.CONFLICT, reason);
    }

    private ResponseEntity<JsonNode> error(HttpStatus status, String reason) {
        return ResponseEntity.status(status).body(json.createObjectNode().put("error", reason));
    }
}
