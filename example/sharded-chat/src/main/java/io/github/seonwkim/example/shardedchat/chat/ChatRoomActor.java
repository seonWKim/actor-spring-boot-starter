package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.AskCommand;
import io.github.seonwkim.actor.SpringActorBehavior;
import io.github.seonwkim.actor.delivery.StaleGenerationException;
import io.github.seonwkim.actor.generation.DeploymentGeneration;
import io.github.seonwkim.actor.SpringBehaviorContext;
import io.github.seonwkim.actor.serialization.JsonSerializable;
import io.github.seonwkim.actor.shard.ShardedEntityType;
import io.github.seonwkim.actor.shard.SpringShardedActor;
import io.github.seonwkim.actor.shard.SpringShardedActorContext;
import io.github.seonwkim.example.shardedchat.events.ChatEvent;
import io.github.seonwkim.example.shardedchat.events.ChatEvents;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A chat room: a sharded entity of type {@value #TYPE_NAME}, one per room id, on whichever node the
 * cluster puts its shard. It keeps its members (user ids, which may run on other nodes) and forwards
 * each {@link Post} to every other member, then replies. A {@code timeoutShare} of the posts, picked by
 * a seeded random source, is dropped without a reply, so the poster's ask times out.
 *
 * <p>Its state is in memory. When it starts (first message, a move to another node, a restart after
 * its node died) it says {@link ChatSimulatorActor.Hello} to the simulator, every second until the
 * simulator answers with {@link Configure}, which carries its settings and members.
 */
@Component
public class ChatRoomActor implements SpringShardedActor<ChatRoomActor.Command> {
    public static final String TYPE_NAME = "ChatRoom";
    public static final ShardedEntityType<Command> TYPE = ShardedEntityType.of(Command.class, TYPE_NAME);
    private static final Object GREET = "greet";
    private static final Duration GREET_EVERY = Duration.ofSeconds(1);

    /** Every message to a room crosses the network, so the command type is {@link JsonSerializable}. */
    public interface Command extends JsonSerializable {}

    /** The room's settings and its members, from the simulator. Replaces what the room had. */
    public record Configure(double timeoutShare, long seed, List<String> members) implements Command {}

    public record Join(String userId) implements Command {}

    public record Leave(String userId) implements Command {}

    /**
     * A message from a member, stamped with the epoch of the generation it was made in. The reply is
     * true once it was forwarded, false when the room refused it because that epoch is no longer the
     * active one. A dropped post gets none.
     */
    public static final class Post extends AskCommand<Boolean> implements Command {
        public final String from;
        public final String text;
        public final long epoch;

        public Post(String from, String text, long epoch) {
            this.from = from;
            this.text = text;
            this.epoch = epoch;
        }
    }

    /** A message from the page to every member, never dropped; the reply names the members it reached. */
    public static final class Broadcast extends AskCommand<Reached> implements Command {
        public final String from;
        public final String text;

        public Broadcast(String from, String text) {
            this.from = from;
            this.text = text;
        }
    }

    public record Reached(List<String> userIds) implements JsonSerializable {}

    /** The simulator closed the room. */
    public record Stop(String reason) implements Command {}

    private enum Greet implements Command {
        INSTANCE
    }

    private final ChatCluster cluster;
    private final ChatEvents events;
    private final DeploymentGeneration generation;

    public ChatRoomActor(ChatCluster cluster, ChatEvents events, DeploymentGeneration generation) {
        this.cluster = cluster;
        this.events = events;
        this.generation = generation;
    }

    @Override
    public ShardedEntityType<Command> entityType() {
        return TYPE;
    }

    private static final class State {
        final SpringBehaviorContext<Command> ctx;
        final Set<String> members = new LinkedHashSet<>();
        boolean configured;
        double timeoutShare;
        Random random;

        State(SpringBehaviorContext<Command> ctx, long seed) {
            this.ctx = ctx;
            this.random = new Random(seed);
        }
    }

    @Override
    public SpringActorBehavior<Command> create(SpringShardedActorContext<Command> context) {
        String me = context.getEntityId();
        return SpringActorBehavior.builder(Command.class, context)
                .withState(ctx -> {
                    State s = new State(ctx, me.hashCode());
                    greet(s, me);
                    return s;
                })
                .onMessage(Greet.class, (s, msg) -> {
                    if (!s.configured) {
                        greet(s, me);
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(Configure.class, (s, msg) -> {
                    s.configured = true;
                    s.timeoutShare = msg.timeoutShare();
                    s.random = new Random(msg.seed());
                    s.members.clear();
                    s.members.addAll(msg.members());
                    return SpringActorBehavior.same();
                })
                .onMessage(Join.class, (s, msg) -> {
                    s.members.add(msg.userId());
                    return SpringActorBehavior.same();
                })
                .onMessage(Leave.class, (s, msg) -> {
                    s.members.remove(msg.userId());
                    return SpringActorBehavior.same();
                })
                .onMessage(Post.class, (s, msg) -> {
                    if (!s.members.contains(msg.from) || s.random.nextDouble() < s.timeoutShare) {
                        return SpringActorBehavior.same();
                    }
                    // A stale post gets "false" at once: the user logs it as fenced instead of timing out.
                    // (generation.ifCurrent(...) would drop it without an answer.)
                    if (!current(msg.epoch)) {
                        events.log(
                                me,
                                ChatEvent.WARN,
                                "fenced",
                                "refused a post from " + msg.from + " stamped with epoch " + msg.epoch
                                        + ": " + generation.color() + " is no longer the active generation");
                        events.message(me, msg.from, Boolean.FALSE);
                        msg.reply(false);
                        return SpringActorBehavior.same();
                    }
                    for (String member : s.members) {
                        if (!member.equals(msg.from)) {
                            deliver(me, member, msg.from, msg.text);
                        }
                    }
                    events.message(me, msg.from, Boolean.TRUE);
                    msg.reply(true);
                    return SpringActorBehavior.same();
                })
                .onMessage(Broadcast.class, (s, msg) -> {
                    events.message(msg.from, me, msg);
                    for (String member : s.members) {
                        deliver(me, member, msg.from, msg.text);
                    }
                    msg.reply(new Reached(List.copyOf(s.members)));
                    return SpringActorBehavior.same();
                })
                .onMessage(Stop.class, (s, msg) -> SpringActorBehavior.stopped())
                .build();
    }

    private void deliver(String roomId, String userId, String from, String text) {
        ChatUserActor.Deliver deliver = new ChatUserActor.Deliver(roomId, from, text);
        events.message(roomId, userId, deliver);
        cluster.user(userId).tell(deliver);
    }

    private void greet(State s, String me) {
        cluster.simulator().tell(new ChatSimulatorActor.Hello(me, cluster.node()));
        s.ctx.startSingleTimer(GREET, Greet.INSTANCE, GREET_EVERY);
    }

    private boolean current(long epoch) {
        try {
            generation.check(epoch);
            return true;
        } catch (StaleGenerationException e) {
            return false;
        }
    }
}
