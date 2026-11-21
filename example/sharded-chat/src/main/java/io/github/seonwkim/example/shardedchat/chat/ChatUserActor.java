package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.AskCommand;
import io.github.seonwkim.actor.SpringActorBehavior;
import io.github.seonwkim.actor.SpringBehaviorContext;
import io.github.seonwkim.actor.generation.DeploymentGeneration;
import io.github.seonwkim.actor.serialization.JsonSerializable;
import io.github.seonwkim.actor.shard.ShardedEntityType;
import io.github.seonwkim.actor.shard.SpringShardedActor;
import io.github.seonwkim.actor.shard.SpringShardedActorContext;
import io.github.seonwkim.example.shardedchat.events.ChatEvent;
import io.github.seonwkim.example.shardedchat.events.ChatEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A chat user: a sharded entity of type {@value #TYPE_NAME}, one per user id. Once in a room it posts a
 * few random words to one of its rooms at random intervals averaging its pace. Each post is an ask to
 * the room, wherever it runs, that times out after {@link #REPLY_TIMEOUT}; the reply or the timeout
 * comes back as a message, so the user never blocks, and it tells the simulator the outcome. It logs
 * joined, left, sent, received and timeout lines.
 *
 * <p>Like a room, it says {@link ChatSimulatorActor.Hello} when it starts, until the simulator answers
 * with {@link Configure}: its pace, seed and rooms.
 */
@Component
public class ChatUserActor implements SpringShardedActor<ChatUserActor.Command> {
    public static final String TYPE_NAME = "ChatUser";
    public static final ShardedEntityType<Command> TYPE = ShardedEntityType.of(Command.class, TYPE_NAME);
    public static final Duration REPLY_TIMEOUT = Duration.ofSeconds(1);
    static final List<String> WORDS = List.of(
            "hello",
            "actor",
            "mailbox",
            "ping",
            "pong",
            "coffee",
            "deploy",
            "ring",
            "message",
            "lunch",
            "supervisor",
            "restart",
            "stream",
            "ok",
            "thanks",
            "later",
            "ship",
            "it",
            "nice",
            "why");
    private static final Object TICK = "tick";
    private static final Object GREET = "greet";
    private static final Duration GREET_EVERY = Duration.ofSeconds(1);

    public interface Command extends JsonSerializable {}

    /** The user's pace (mean milliseconds between posts, 0 for none), seed and rooms. Replaces what it had. */
    public record Configure(long paceMillis, long seed, List<String> rooms) implements Command {}

    public record Joined(String roomId) implements Command {}

    /** The user left the room; reason, when not null, says why (e.g. "room stopped"). */
    public record Left(String roomId, String reason) implements Command {}

    /** A message another member, or the page, sent to one of the user's rooms. */
    public record Deliver(String roomId, String from, String text) implements Command {}

    /** The new mean milliseconds between posts; 0 stops posting. */
    public record Pace(long paceMillis) implements Command {}

    /** A message from the page to this user alone; the reply is true. */
    public static final class Direct extends AskCommand<Boolean> implements Command {
        public final String from;
        public final String text;

        public Direct(String from, String text) {
            this.from = from;
            this.text = text;
        }
    }

    /** The simulator removed the user. */
    public record Stop(String reason) implements Command {}

    private enum Tick implements Command {
        INSTANCE
    }

    private enum Greet implements Command {
        INSTANCE
    }

    /** The outcome of a post: the room forwarded it (ok), refused it as stale (refused), or neither in time. */
    private record Replied(String roomId, String text, boolean ok, boolean refused) implements Command {}

    private final ChatCluster cluster;
    private final ChatEvents events;
    private final DeploymentGeneration generation;

    public ChatUserActor(ChatCluster cluster, ChatEvents events, DeploymentGeneration generation) {
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
        final Set<String> rooms = new LinkedHashSet<>();
        boolean configured;
        // Whether the last tick found this color on standby; logged once, not on every tick.
        boolean standby;
        long paceMillis;
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
                    s.paceMillis = msg.paceMillis();
                    s.random = new Random(msg.seed());
                    s.rooms.clear();
                    s.rooms.addAll(msg.rooms());
                    scheduleTick(s);
                    return SpringActorBehavior.same();
                })
                .onMessage(Joined.class, (s, msg) -> {
                    if (s.rooms.add(msg.roomId())) {
                        info(me, "joined", "joined " + msg.roomId());
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(Left.class, (s, msg) -> {
                    if (s.rooms.remove(msg.roomId())) {
                        String why = msg.reason() == null ? "" : " (" + msg.reason() + ")";
                        info(me, "left", "left " + msg.roomId() + why);
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(Pace.class, (s, msg) -> {
                    if (msg.paceMillis() == s.paceMillis || s.random == null) {
                        // Not configured yet (its Configure brings the pace), or nothing changed.
                        s.paceMillis = msg.paceMillis();
                        return SpringActorBehavior.same();
                    }
                    s.paceMillis = msg.paceMillis();
                    rescheduleTick(s);
                    return SpringActorBehavior.same();
                })
                .onMessage(Deliver.class, (s, msg) -> {
                    info(me, "received", "received \"" + msg.text() + "\" from " + msg.from() + " in " + msg.roomId());
                    return SpringActorBehavior.same();
                })
                .onMessage(Direct.class, (s, msg) -> {
                    events.message(msg.from, me, msg);
                    info(me, "received", "received \"" + msg.text + "\" from " + msg.from);
                    msg.reply(true);
                    return SpringActorBehavior.same();
                })
                .onMessage(Tick.class, (s, msg) -> {
                    if (!s.rooms.isEmpty()) {
                        post(s, me);
                    }
                    scheduleTick(s);
                    return SpringActorBehavior.same();
                })
                .onMessage(Replied.class, (s, msg) -> {
                    if (msg.refused()) {
                        events.log(
                                me,
                                ChatEvent.WARN,
                                "fenced",
                                msg.roomId() + " refused \"" + msg.text() + "\": its epoch is no longer active");
                        return SpringActorBehavior.same();
                    }
                    if (!msg.ok()) {
                        events.log(
                                me,
                                ChatEvent.WARN,
                                "timeout",
                                "no reply from " + msg.roomId() + " within " + REPLY_TIMEOUT.toMillis() + " ms to \""
                                        + msg.text() + "\"");
                    }
                    cluster.simulator().tell(new ChatSimulatorActor.Outcome(me, msg.ok()));
                    return SpringActorBehavior.same();
                })
                .onMessage(Stop.class, (s, msg) -> SpringActorBehavior.stopped())
                .build();
    }

    // Stamps the post with the active epoch; on the standby color there is none, and the user stays quiet.
    private void post(State s, String me) {
        if (!generation.isActive()) {
            if (!s.standby) {
                s.standby = true;
                events.log(me, ChatEvent.INFO, "standby", "paused: " + generation.color() + " is not the active color");
            }
            return;
        }
        s.standby = false;
        // Without blue-green (bootRun, the tests) the starter's generation is always active and stamps 0.
        long epoch = generation.stamp();
        List<String> rooms = new ArrayList<>(s.rooms);
        String roomId = rooms.get(s.random.nextInt(rooms.size()));
        String text = words(s.random);
        info(me, "sent", "sent \"" + text + "\" to " + roomId);
        ChatRoomActor.Post post = new ChatRoomActor.Post(me, text, epoch);
        events.message(me, roomId, post);
        // The ask's outcome, a reply or a timeout, comes back to this actor as a message.
        s.ctx.askToSelf(
                cluster.room(roomId),
                post,
                REPLY_TIMEOUT,
                (ok, error) -> new Replied(
                        roomId,
                        text,
                        error == null && Boolean.TRUE.equals(ok),
                        error == null && Boolean.FALSE.equals(ok)));
    }

    // Uniform between half and one and a half of the pace; a new timer replaces the running one.
    private static void scheduleTick(State s) {
        if (s.paceMillis <= 0) {
            s.ctx.cancelTimer(TICK);
            return;
        }
        long delay = s.paceMillis / 2 + (long) (s.random.nextDouble() * s.paceMillis);
        s.ctx.startSingleTimer(TICK, Tick.INSTANCE, Duration.ofMillis(Math.max(1, delay)));
    }

    // After a pace change: the next post comes anywhere within one pace. Every user gets the change at the
    // same moment, so waiting at least half a pace (scheduleTick) would silence them all for that long.
    private static void rescheduleTick(State s) {
        if (s.paceMillis <= 0) {
            s.ctx.cancelTimer(TICK);
            return;
        }
        long delay = (long) (s.random.nextDouble() * s.paceMillis);
        s.ctx.startSingleTimer(TICK, Tick.INSTANCE, Duration.ofMillis(Math.max(1, delay)));
    }

    private void info(String me, String kind, String text) {
        events.log(me, ChatEvent.INFO, kind, text);
    }

    private void greet(State s, String me) {
        cluster.simulator().tell(new ChatSimulatorActor.Hello(me, cluster.node()));
        s.ctx.startSingleTimer(GREET, Greet.INSTANCE, GREET_EVERY);
    }

    private static String words(Random random) {
        int count = 1 + random.nextInt(4);
        List<String> picked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            picked.add(WORDS.get(random.nextInt(WORDS.size())));
        }
        return String.join(" ", picked);
    }
}
