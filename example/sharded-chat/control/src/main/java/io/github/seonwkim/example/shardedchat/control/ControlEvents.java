package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The page's one event stream: both colors' chat events (relayed by {@link EventRelay}, ids with their
 * color) and the control app's own: pipeline lines from the {@code deployer}, and node status. Keeps the
 * last {@value #KEEP} log events for a page that opens later.
 */
@Component
public class ControlEvents {

    static final int KEEP = 500;
    static final int KEEP_DEPLOY = 100;
    static final String DEPLOYER = "deployer";

    private final ObjectMapper json;
    private final Sinks.Many<ServerSentEvent<String>> sink =
            Sinks.many().multicast().directBestEffort();
    private final Deque<JsonNode> logs = new ArrayDeque<>();
    // The pipeline's lines apart, so the chat's chatter does not push them out.
    private final Deque<JsonNode> deploys = new ArrayDeque<>();

    public ControlEvents(ObjectMapper json) {
        this.json = json;
    }

    /** An event of the given type ({@code message}, {@code log}, {@code member}) to every open page. */
    public void emit(String type, JsonNode data) {
        if ("log".equals(type)) {
            boolean deploy = DEPLOYER.equals(data.path("actorId").asText());
            Deque<JsonNode> kept = deploy ? deploys : logs;
            synchronized (logs) {
                kept.addLast(data);
                while (kept.size() > (deploy ? KEEP_DEPLOY : KEEP)) {
                    kept.removeFirst();
                }
            }
        }
        sink.tryEmitNext(ServerSentEvent.builder(data.toString()).event(type).build());
    }

    /** A pipeline line from the deployer. */
    public void deploy(String level, String text) {
        ObjectNode data = json.createObjectNode()
                .put("type", "log")
                .put("actorId", DEPLOYER)
                .put("message", text)
                .put("timestamp", Instant.now().toString())
                .put("kind", "deploy")
                .put("level", level);
        emit("log", data);
    }

    /** A node's status changed, as the control app saw it. */
    public void member(int node, String status) {
        ObjectNode data = json.createObjectNode()
                .put("type", "member")
                .put("actorId", "node-" + node)
                .put("message", status)
                .put("timestamp", Instant.now().toString())
                .put("node", node);
        emit("member", data);
    }

    public Flux<ServerSentEvent<String>> stream() {
        return sink.asFlux();
    }

    /** The kept log events, the pipeline's among them, oldest first. */
    public List<JsonNode> logs() {
        List<JsonNode> all = new ArrayList<>();
        synchronized (logs) {
            all.addAll(deploys);
            all.addAll(logs);
        }
        all.sort(Comparator.comparing(event -> event.path("timestamp").asText()));
        return all;
    }
}
