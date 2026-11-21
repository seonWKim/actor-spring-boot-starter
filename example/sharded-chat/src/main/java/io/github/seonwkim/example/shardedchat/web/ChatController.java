package io.github.seonwkim.example.shardedchat.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.seonwkim.example.shardedchat.chat.ChatService;
import io.github.seonwkim.example.shardedchat.chat.ChatSettings;
import io.github.seonwkim.example.shardedchat.chat.ChatSimulatorActor;
import io.github.seonwkim.example.shardedchat.chat.InvalidFieldException;
import io.github.seonwkim.example.shardedchat.events.ChatEvent;
import io.github.seonwkim.example.shardedchat.events.ChatEvents;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;

/** The chat API under {@code /api/chat}, the same on every node (README.md, "HTTP API"). */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    public record Settings(ChatSettings settings) {}

    public record RoomAdded(String roomId, ChatSettings settings) {}

    private final ChatService chat;
    private final ChatEvents events;

    public ChatController(ChatService chat, ChatEvents events) {
        this.chat = chat;
        this.events = events;
    }

    @GetMapping("/state")
    public CompletionStage<ChatSimulatorActor.ChatState> state() {
        return chat.state();
    }

    @PutMapping("/rooms")
    public CompletionStage<Settings> rooms(@RequestBody(required = false) JsonNode body) {
        return chat.update(RequestFields.integer(body, "rooms"), null, null).thenApply(Settings::new);
    }

    @PutMapping("/users")
    public CompletionStage<Settings> users(@RequestBody(required = false) JsonNode body) {
        return chat.update(null, RequestFields.integer(body, "users"), null).thenApply(Settings::new);
    }

    @PutMapping("/rate")
    public CompletionStage<Settings> rate(@RequestBody(required = false) JsonNode body) {
        return chat.update(null, null, RequestFields.number(body, "messageRate"))
                .thenApply(Settings::new);
    }

    @PostMapping("/rooms")
    public CompletionStage<RoomAdded> addRoom(@RequestBody(required = false) JsonNode body) {
        return chat.addRoom(RequestFields.optionalWhole(body, "number"))
                .thenApply(added -> new RoomAdded(added.roomId(), added.settings()));
    }

    @PostMapping("/send")
    public CompletionStage<ChatService.Delivery> send(@RequestBody(required = false) JsonNode body) {
        String to = RequestFields.text(body, "to", 64);
        return chat.send(to, RequestFields.text(body, "text", ChatService.MAX_TEXT));
    }

    @PostMapping("/broadcast")
    public CompletionStage<ChatService.Delivery> broadcast(@RequestBody(required = false) JsonNode body) {
        String room = RequestFields.text(body, "room", 64);
        return chat.broadcast(room, RequestFields.text(body, "text", ChatService.MAX_TEXT));
    }

    /** The log lines this node holds, oldest first. */
    @GetMapping("/logs")
    public List<ChatEvent> logs() {
        return events.logs();
    }

    /** Every event from now on as server-sent events, named by type: message, log and member. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> events() {
        return events.stream()
                .map(e -> ServerSentEvent.builder(e).event(e.type()).build());
    }

    @ExceptionHandler(InvalidFieldException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badField(InvalidFieldException e) {
        return Map.of("error", e.getMessage(), "field", e.field());
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(ServerWebInputException e) {
        return Map.of("error", "the body must be JSON with the fields named in README.md");
    }

    @ExceptionHandler(ChatService.NotReadyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> notReady(ChatService.NotReadyException e) {
        return Map.of("error", e.getMessage());
    }
}
