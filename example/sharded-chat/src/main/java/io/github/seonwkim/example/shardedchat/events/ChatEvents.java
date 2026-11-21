package io.github.seonwkim.example.shardedchat.events;

import io.github.seonwkim.actor.SpringActorSystem;
import io.github.seonwkim.actor.topic.SpringTopicManager;
import io.github.seonwkim.actor.topic.SpringTopicRef;
import io.github.seonwkim.actor.topic.TopicName;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Where the chat's events go. An actor publishes a message or log event on the distributed topic
 * {@value #TOPIC_NAME}; every node subscribes to it with one {@link EventFeedActor}, so each node sees
 * every event, wherever the actor that sent it runs. The page reads one node: its event stream
 * ({@link #stream()}) and its last log lines ({@link #logs()}), kept in memory only: the newest
 * {@value #CAPACITY}, none older than {@link #RETENTION}.
 */
@Component
public class ChatEvents {
    public static final String TOPIC_NAME = "chat-events";
    static final TopicName<ChatEvent> TOPIC = TopicName.of(ChatEvent.class, TOPIC_NAME);
    static final int CAPACITY = 500;
    static final Duration RETENTION = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(ChatEvents.class);

    private final SpringActorSystem actorSystem;
    private final SpringTopicManager topics;
    private final int node;
    private final Sinks.Many<ChatEvent> sink = Sinks.many().multicast().directBestEffort();
    private final Deque<ChatEvent> logs = new ArrayDeque<>();
    private volatile SpringTopicRef<ChatEvent> topic;

    public ChatEvents(
            SpringActorSystem actorSystem, SpringTopicManager topics, @Value("${sharded-chat.node:1}") int node) {
        this.actorSystem = actorSystem;
        this.topics = topics;
        this.node = node;
    }

    /** Joins the topic: spawns this node's feed actor and subscribes it. */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        SpringTopicRef<ChatEvent> ref = topics.topic(TOPIC).getOrCreate();
        actorSystem
                .actor(EventFeedActor.class)
                .withId("chat-event-feed")
                .spawn()
                .whenComplete((feed, error) -> {
                    if (error != null) {
                        log.error("Could not spawn the event feed", error);
                    } else {
                        ref.subscribe(feed);
                        topic = ref;
                    }
                });
    }

    /** A message from sender to receiver, sent now by an actor on this node. */
    public void message(String sender, String receiver, Object message) {
        publish(ChatEvent.message(sender, receiver, message, node));
    }

    /** A log line of an actor on this node; level is {@link ChatEvent#INFO} or {@link ChatEvent#WARN}. */
    public void log(String actorId, String level, String kind, String text) {
        publish(ChatEvent.log(actorId, level, kind, text, node));
    }

    private void publish(ChatEvent event) {
        SpringTopicRef<ChatEvent> ref = topic;
        if (ref != null) {
            ref.publish(event);
        }
    }

    /** An event for this node's page only: the feed's events and this node's view of the cluster. */
    void emit(ChatEvent event) {
        if (ChatEvent.LOG.equals(event.type())) {
            synchronized (logs) {
                while (logs.size() >= CAPACITY) {
                    logs.removeFirst();
                }
                logs.addLast(event);
            }
        }
        synchronized (sink) {
            sink.tryEmitNext(event);
        }
    }

    /** Every event from now on, as this node sees it. */
    public Flux<ChatEvent> stream() {
        return sink.asFlux();
    }

    /** The log lines this node holds, oldest first. */
    public List<ChatEvent> logs() {
        Instant cutoff = Instant.now().minus(RETENTION);
        synchronized (logs) {
            while (!logs.isEmpty() && Instant.parse(logs.peekFirst().timestamp()).isBefore(cutoff)) {
                logs.removeFirst();
            }
            return List.copyOf(logs);
        }
    }
}
