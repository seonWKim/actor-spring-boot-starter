package io.github.seonwkim.example.shardedchat.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * One subscription per running color to a node's {@code GET /api/chat/events}. A node streams every
 * event of its whole cluster (the chat's topic brings them to every node), so one node per color is
 * enough. Each event's ids get the color, and the event a {@code color} field. A subscription that ends
 * (its node stopped) is opened again on another node at the next check.
 */
@Component
public class EventRelay {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> EVENT =
            new ParameterizedTypeReference<>() {};

    private final Nodes nodes;
    private final ControlEvents events;
    private final ObjectMapper json;
    private final Map<String, Disposable> open = new ConcurrentHashMap<>();

    public EventRelay(Nodes nodes, ControlEvents events, ObjectMapper json) {
        this.nodes = nodes;
        this.events = events;
        this.json = json;
    }

    /** Opens the color's subscription if it has none and one of its nodes answers; closes it when none runs. */
    void check(String color) {
        Disposable current = open.get(color);
        if (current != null && !current.isDisposed()) {
            if (nodes.live(color).isEmpty()) {
                current.dispose();
                open.remove(color);
            }
            return;
        }
        nodes.serving(color).ifPresent(node -> open.put(color, subscribe(color, node.node)));
    }

    private Disposable subscribe(String color, int node) {
        return nodes.client()
                .get()
                .uri(nodes.base(node) + "/api/chat/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(EVENT)
                .doFinally(signal -> open.remove(color))
                .subscribe(event -> relay(color, event), error -> {});
    }

    private void relay(String color, ServerSentEvent<String> event) {
        if (event.data() == null || event.event() == null) {
            return;
        }
        try {
            JsonNode data = Ids.qualify(json.readTree(event.data()), color);
            if (data instanceof ObjectNode object) {
                object.put("color", color);
                // The control app reports node status itself, for both colors.
                if (!"member".equals(event.event())) {
                    events.emit(event.event(), object);
                }
            }
        } catch (Exception e) {
            // Not JSON: not one of the chat's events.
        }
    }
}
