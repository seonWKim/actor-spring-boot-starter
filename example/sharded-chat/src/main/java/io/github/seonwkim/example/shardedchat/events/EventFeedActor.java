package io.github.seonwkim.example.shardedchat.events;

import io.github.seonwkim.actor.SpringActor;
import io.github.seonwkim.actor.SpringActorBehavior;
import io.github.seonwkim.actor.SpringActorContext;
import org.springframework.stereotype.Component;

/** This node's subscriber of the chat event topic: hands each event to {@link ChatEvents} for the page. */
@Component
public class EventFeedActor implements SpringActor<ChatEvent> {
    private final ChatEvents events;

    public EventFeedActor(ChatEvents events) {
        this.events = events;
    }

    @Override
    public SpringActorBehavior<ChatEvent> create(SpringActorContext ctx) {
        return SpringActorBehavior.builder(ChatEvent.class, ctx)
                .onMessage(ChatEvent.class, (behaviorCtx, event) -> {
                    events.emit(event);
                    return SpringActorBehavior.same();
                })
                .build();
    }
}
