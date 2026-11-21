package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.AskCommand;
import io.github.seonwkim.actor.SpringActor;
import io.github.seonwkim.actor.SpringActorBehavior;
import io.github.seonwkim.actor.generation.DeploymentGeneration;
import io.github.seonwkim.actor.SpringActorContext;
import io.github.seonwkim.actor.SpringBehaviorContext;
import io.github.seonwkim.actor.serialization.JsonSerializable;
import io.github.seonwkim.example.shardedchat.events.ChatEvent;
import io.github.seonwkim.example.shardedchat.events.ChatEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Runs the chat simulation, as a cluster singleton: one for the whole cluster, on the oldest node. It
 * starts a run with the configured settings when it starts, and holds who is in which room.
 *
 * <ul>
 *   <li>Every {@link #SPAWN_MIN} to {@link #SPAWN_MAX} it closes a random room about one tick in
 *       {@link #STOP_ONE_IN}, else opens one while there are fewer than the rooms setting. A new room
 *       takes in every user without a room and about one in four of the others.
 *   <li>Users are added one every {@link #USER_EVERY}, so a large run fills in over a few seconds.
 *   <li>Every {@link #MOVE_MIN} to {@link #MOVE_MAX} it moves one random user into, between or out of
 *       rooms.
 * </ul>
 *
 * <p>To start a room or a user it only sends it {@link ChatRoomActor.Configure} or {@link
 * ChatUserActor.Configure}: a sharded entity starts on its first message, on the node its shard is on.
 * Each entity then says {@link Hello} with its node, which the state view shows; it says it again
 * whenever it starts elsewhere, and gets its settings and memberships back. An id that is not part of
 * the run (a room it closed, or one of a run before this singleton moved) is told to stop.
 *
 * <p>Blue-green: only the active color chats. The simulator starts its run once this node's color is
 * active ({@link DeploymentGeneration}), and on the standby color it pauses: no rooms open or close, nobody moves,
 * and the users stop posting because they can't stamp a post. Its rooms and users keep their state, so
 * a rollback to this color resumes where it was.
 *
 * <p>When a node leaves, the simulator configures again every room and user that ran there ({@link
 * NodeLost}), so each starts at once on a node that is still up. If the singleton's own node goes
 * down, the singleton starts on another node with a new run.
 */
@Component
public class ChatSimulatorActor implements SpringActor<ChatSimulatorActor.Command> {
    public static final String ROOM_PREFIX = "chat-room-";
    public static final String USER_PREFIX = "chat-user-";
    /** The highest room number a person may pick. */
    public static final long MAX_ROOM_NUMBER = 999_999;
    /** The simulator's id in log lines. */
    static final String SIMULATOR = ChatCluster.SIMULATOR_ID;

    static final Duration SPAWN_MIN = Duration.ofSeconds(2);
    static final Duration SPAWN_MAX = Duration.ofSeconds(6);
    static final Duration MOVE_MIN = Duration.ofSeconds(1);
    static final Duration MOVE_MAX = Duration.ofSeconds(3);
    static final Duration USER_EVERY = Duration.ofMillis(30);
    static final int STOP_ONE_IN = 4;
    /** How long the simulator waits for {@code start-with-nodes} nodes before it starts anyway. */
    static final Duration WAIT_FOR_NODES = Duration.ofSeconds(30);

    static final Duration WAIT_EVERY = Duration.ofMillis(500);

    /** The simulator is reached through a singleton proxy from any node, so its messages are serializable. */
    public interface Command extends JsonSerializable {}

    /** Sets the given (non-null) values at once; the reply is the settings after. */
    public static final class Update extends AskCommand<ChatSettings> implements Command {
        public final Integer rooms;
        public final Integer users;
        public final Double messageRate;

        public Update(Integer rooms, Integer users, Double messageRate) {
            this.rooms = rooms;
            this.users = users;
            this.messageRate = messageRate;
        }
    }

    /**
     * Raises the rooms setting by one and opens that room now, as {@code chat-room-<number>} when a
     * number is given, else with the next free number. The reply names it, or says why not.
     */
    public static final class AddRoom extends AskCommand<RoomAdded> implements Command {
        public final Long number;

        public AddRoom(Long number) {
            this.number = number;
        }
    }

    public record RoomAdded(String roomId, ChatSettings settings, String refused) implements JsonSerializable {}

    public static final class GetState extends AskCommand<ChatState> implements Command {}

    /** Whether the id is a room or a user of the run. */
    public static final class Has extends AskCommand<Boolean> implements Command {
        public final String id;

        public Has(String id) {
            this.id = id;
        }
    }

    /** Told by a room or a user each time it starts: its id and the node it runs on. */
    public record Hello(String id, int node) implements Command {}

    /**
     * A node left the cluster. Every room and user that ran there is configured again: the message
     * starts it on a node that is still up (its shard moved there), and it says Hello from there.
     */
    public record NodeLost(int node) implements Command {}

    /** Told by a user after each post: whether the room replied in time. */
    public record Outcome(String userId, boolean replied) implements Command {}

    public record RoomView(String id, List<String> members, Integer node) {}

    public record UserView(String id, List<String> rooms, Integer node) {}

    public record Counts(long replied, long timedOut) {}

    /**
     * The run: its settings, rooms with their members, users with their rooms, post outcomes, and whether
     * it is running (started, and this color is active).
     */
    public record ChatState(
            ChatSettings settings, List<RoomView> rooms, List<UserView> users, Counts counts, boolean running)
            implements JsonSerializable {}

    private enum Tick implements Command {
        SPAWN,
        MOVE,
        USER,
        WAIT
    }

    private final ChatCluster cluster;
    private final ChatEvents events;
    private final ChatProperties properties;
    private final DeploymentGeneration generation;

    public ChatSimulatorActor(
            ChatCluster cluster,
            ChatEvents events,
            ChatProperties properties,
            DeploymentGeneration generation) {
        this.cluster = cluster;
        this.events = events;
        this.properties = properties;
        this.generation = generation;
    }

    private static final class State {
        final SpringBehaviorContext<Command> ctx;
        // Room id -> its members, user id -> its rooms; in order of creation.
        final Map<String, Set<String>> rooms = new LinkedHashMap<>();
        final Map<String, Set<String>> users = new LinkedHashMap<>();
        final Map<String, Integer> nodes = new HashMap<>();
        ChatSettings settings;
        long seed;
        Random spawnRandom;
        Random random;
        int usersLeft;
        long nextRoom;
        long nextUser;
        long replied;
        long timedOut;
        boolean started;
        boolean paused;
        // The pace the users last got, in Configure or Pace.
        long sentPace = -1;
        long waitUntil;

        State(SpringBehaviorContext<Command> ctx) {
            this.ctx = ctx;
        }
    }

    @Override
    public SpringActorBehavior<Command> create(SpringActorContext context) {
        return SpringActorBehavior.builder(Command.class, context)
                .withState(ctx -> {
                    State s = new State(ctx);
                    s.settings = properties.settings();
                    s.waitUntil = System.currentTimeMillis() + WAIT_FOR_NODES.toMillis();
                    startWhenNodesAreUp(s);
                    return s;
                })
                .onMessage(Update.class, (s, msg) -> {
                    s.settings = s.settings.with(msg.rooms, msg.users, msg.messageRate);
                    if (s.started) {
                        resize(s);
                    }
                    msg.reply(s.settings);
                    return SpringActorBehavior.same();
                })
                .onMessage(AddRoom.class, (s, msg) -> {
                    msg.reply(addRoom(s, msg.number));
                    return SpringActorBehavior.same();
                })
                .onMessage(GetState.class, (s, msg) -> {
                    msg.reply(state(s, s.started && !s.paused));
                    return SpringActorBehavior.same();
                })
                .onMessage(Has.class, (s, msg) -> {
                    msg.reply(s.rooms.containsKey(msg.id) || s.users.containsKey(msg.id));
                    return SpringActorBehavior.same();
                })
                .onMessage(Hello.class, (s, msg) -> {
                    if (s.rooms.containsKey(msg.id())) {
                        s.nodes.put(msg.id(), msg.node());
                        configureRoom(s, msg.id());
                    } else if (s.users.containsKey(msg.id())) {
                        s.nodes.put(msg.id(), msg.node());
                        configureUser(s, msg.id());
                    } else {
                        stop(msg.id(), "not in the run");
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(NodeLost.class, (s, msg) -> {
                    List<String> lost = s.nodes.entrySet().stream()
                            .filter(e -> e.getValue() == msg.node())
                            .map(Map.Entry::getKey)
                            .toList();
                    for (String id : lost) {
                        s.nodes.remove(id);
                        if (s.rooms.containsKey(id)) {
                            configureRoom(s, id);
                        } else {
                            configureUser(s, id);
                        }
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(Outcome.class, (s, msg) -> {
                    if (!s.users.containsKey(msg.userId())) {
                        stop(msg.userId(), "not in the run");
                    } else if (msg.replied()) {
                        s.replied++;
                    } else {
                        s.timedOut++;
                    }
                    return SpringActorBehavior.same();
                })
                .onMessage(Tick.class, (s, msg) -> {
                    if (msg != Tick.WAIT && standby(s)) {
                        s.ctx.startSingleTimer(msg, msg, WAIT_EVERY);
                        return SpringActorBehavior.same();
                    }
                    switch (msg) {
                        case SPAWN -> spawnTick(s);
                        case MOVE -> moveTick(s);
                        case USER -> userTick(s);
                        case WAIT -> startWhenNodesAreUp(s);
                    }
                    return SpringActorBehavior.same();
                })
                .build();
    }

    // A new shard goes to the node holding the fewest shards of its type, among the nodes up at that
    // moment: started before the other nodes join, every room and user would land on this node.
    // It also waits until this color is active: the standby color has no run until it is activated.
    private void startWhenNodesAreUp(State s) {
        long up = cluster.upMembers();
        boolean nodesUp = up >= properties.nodesToWaitFor() || System.currentTimeMillis() >= s.waitUntil;
        if (nodesUp && generation.isActive()) {
            events.log(SIMULATOR, ChatEvent.INFO, "run", "run started: " + generation.color() + " is active");
            start(s);
        } else {
            s.ctx.startSingleTimer(Tick.WAIT, Tick.WAIT, WAIT_EVERY);
        }
    }

    // Whether this color is on standby now; logs the change when it pauses or resumes.
    private boolean standby(State s) {
        boolean standby = !generation.isActive();
        if (standby != s.paused) {
            s.paused = standby;
            events.log(
                    SIMULATOR,
                    ChatEvent.INFO,
                    standby ? "standby" : "run",
                    standby
                            ? "paused: " + generation.color() + " is no longer the active color"
                            : "resumed: " + generation.color() + " is active again");
        }
        return standby;
    }

    private void start(State s) {
        s.started = true;
        s.sentPace = s.settings.pace().toMillis();
        s.seed = properties.seed() != null ? properties.seed() : ThreadLocalRandom.current().nextLong();
        s.spawnRandom = new Random(s.seed);
        s.random = new Random(s.seed + 1);
        if (s.settings.rooms() > 0) {
            openRoom(s, nextRoomId(s));
        }
        s.usersLeft = s.settings.users();
        if (s.usersLeft > 0) {
            s.ctx.startSingleTimer(Tick.USER, Tick.USER, USER_EVERY);
        }
        s.ctx.startSingleTimer(Tick.SPAWN, Tick.SPAWN, between(s.spawnRandom, SPAWN_MIN, SPAWN_MAX));
        s.ctx.startSingleTimer(Tick.MOVE, Tick.MOVE, between(s.random, MOVE_MIN, MOVE_MAX));
    }

    private RoomAdded addRoom(State s, Long number) {
        if (!s.started) {
            return new RoomAdded(null, s.settings, "the run starts once the cluster's nodes are up");
        }
        if (s.settings.rooms() >= ChatSettings.MAX_ROOMS) {
            return new RoomAdded(null, s.settings, "rooms is at its most, " + ChatSettings.MAX_ROOMS);
        }
        if (number != null && (number < 1 || number > MAX_ROOM_NUMBER)) {
            return new RoomAdded(null, s.settings, "room number must be 1 to " + MAX_ROOM_NUMBER);
        }
        if (number != null && s.rooms.containsKey(ROOM_PREFIX + number)) {
            return new RoomAdded(null, s.settings, ROOM_PREFIX + number + " already exists");
        }
        s.settings = s.settings.with(s.settings.rooms() + 1, null, null);
        String id = number == null ? nextRoomId(s) : ROOM_PREFIX + number;
        openRoom(s, id);
        return new RoomAdded(id, s.settings, null);
    }

    // Brings the rooms and users to the settings now, newest first out, and paces every user anew.
    private void resize(State s) {
        while (s.rooms.size() > s.settings.rooms()) {
            closeRoom(s, newest(s.rooms));
        }
        while (s.rooms.size() < s.settings.rooms()) {
            openRoom(s, nextRoomId(s));
        }
        int planned = s.users.size() + s.usersLeft;
        if (planned < s.settings.users()) {
            if (s.usersLeft == 0) {
                s.ctx.startSingleTimer(Tick.USER, Tick.USER, USER_EVERY);
            }
            s.usersLeft += s.settings.users() - planned;
        } else {
            s.usersLeft -= Math.min(s.usersLeft, planned - s.settings.users());
            while (s.users.size() > s.settings.users()) {
                removeUser(s, newest(s.users));
            }
        }
        // Only a new pace goes to the users; each user also ignores a pace it already has.
        long pace = s.settings.pace().toMillis();
        if (pace != s.sentPace) {
            s.sentPace = pace;
            s.users.keySet().forEach(id -> cluster.user(id).tell(new ChatUserActor.Pace(pace)));
        }
    }

    private void spawnTick(State s) {
        if (!s.rooms.isEmpty() && s.spawnRandom.nextInt(STOP_ONE_IN) == 0) {
            closeRoom(s, pick(s.spawnRandom, List.copyOf(s.rooms.keySet())));
        } else if (s.rooms.size() < s.settings.rooms()) {
            openRoom(s, nextRoomId(s));
        }
        s.ctx.startSingleTimer(Tick.SPAWN, Tick.SPAWN, between(s.spawnRandom, SPAWN_MIN, SPAWN_MAX));
    }

    private void userTick(State s) {
        if (s.usersLeft > 0) {
            s.usersLeft--;
            addUser(s);
        }
        if (s.usersLeft > 0) {
            s.ctx.startSingleTimer(Tick.USER, Tick.USER, USER_EVERY);
        }
    }

    private void moveTick(State s) {
        if (!s.rooms.isEmpty() && !s.users.isEmpty()) {
            String user = pick(s.random, List.copyOf(s.users.keySet()));
            Set<String> mine = s.users.get(user);
            List<String> others = s.rooms.keySet().stream()
                    .filter(r -> !mine.contains(r))
                    .toList();
            if (mine.isEmpty()) {
                join(s, user, pick(s.random, others));
            } else {
                String current = pick(s.random, List.copyOf(mine));
                int move = s.random.nextInt(3);
                if (move == 0 && !others.isEmpty()) {
                    join(s, user, pick(s.random, others));
                } else {
                    leave(s, user, current, null);
                    if (move == 1 && !others.isEmpty()) {
                        join(s, user, pick(s.random, others));
                    }
                }
            }
        }
        s.ctx.startSingleTimer(Tick.MOVE, Tick.MOVE, between(s.random, MOVE_MIN, MOVE_MAX));
    }

    private void openRoom(State s, String id) {
        s.rooms.put(id, new LinkedHashSet<>());
        configureRoom(s, id);
        events.log(id, ChatEvent.INFO, "room_created", "room created");
        for (Map.Entry<String, Set<String>> user : List.copyOf(s.users.entrySet())) {
            if (user.getValue().isEmpty() || s.random.nextInt(4) == 0) {
                join(s, user.getKey(), id);
            }
        }
    }

    private void closeRoom(State s, String id) {
        for (String user : List.copyOf(s.rooms.get(id))) {
            leave(s, user, id, "room stopped");
        }
        s.rooms.remove(id);
        s.nodes.remove(id);
        stop(id, "room stopped");
        events.log(id, ChatEvent.INFO, "room_stopped", "room stopped");
    }

    private void addUser(State s) {
        String id = USER_PREFIX + ++s.nextUser;
        s.users.put(id, new LinkedHashSet<>());
        configureUser(s, id);
        if (!s.rooms.isEmpty()) {
            join(s, id, pick(s.random, List.copyOf(s.rooms.keySet())));
        }
    }

    private void removeUser(State s, String id) {
        for (String room : List.copyOf(s.users.get(id))) {
            leave(s, id, room, null);
        }
        s.users.remove(id);
        s.nodes.remove(id);
        stop(id, "removed");
    }

    // The room learns first, so a post the user makes after Joined finds it a member.
    private void join(State s, String user, String room) {
        s.rooms.get(room).add(user);
        s.users.get(user).add(room);
        cluster.room(room).tell(new ChatRoomActor.Join(user));
        cluster.user(user).tell(new ChatUserActor.Joined(room));
    }

    private void leave(State s, String user, String room, String reason) {
        s.rooms.get(room).remove(user);
        s.users.get(user).remove(room);
        cluster.room(room).tell(new ChatRoomActor.Leave(user));
        cluster.user(user).tell(new ChatUserActor.Left(room, reason));
    }

    private void configureRoom(State s, String id) {
        cluster.room(id)
                .tell(new ChatRoomActor.Configure(
                        s.settings.timeoutShare(), s.seed + id.hashCode(), List.copyOf(s.rooms.get(id))));
    }

    private void configureUser(State s, String id) {
        cluster.user(id)
                .tell(new ChatUserActor.Configure(
                        s.settings.pace().toMillis(), s.seed + id.hashCode(), List.copyOf(s.users.get(id))));
    }

    private void stop(String id, String reason) {
        if (id.startsWith(ROOM_PREFIX)) {
            cluster.room(id).tell(new ChatRoomActor.Stop(reason));
        } else if (id.startsWith(USER_PREFIX)) {
            cluster.user(id).tell(new ChatUserActor.Stop(reason));
        }
    }

    // The next free number: a number a person chose may already be taken.
    private static String nextRoomId(State s) {
        String id;
        do {
            id = ROOM_PREFIX + ++s.nextRoom;
        } while (s.rooms.containsKey(id));
        return id;
    }

    private static ChatState state(State s, boolean running) {
        List<RoomView> rooms = new ArrayList<>();
        s.rooms.forEach((id, members) -> rooms.add(new RoomView(id, List.copyOf(members), s.nodes.get(id))));
        List<UserView> users = new ArrayList<>();
        s.users.forEach((id, in) -> users.add(new UserView(id, List.copyOf(in), s.nodes.get(id))));
        return new ChatState(s.settings, rooms, users, new Counts(s.replied, s.timedOut), running);
    }

    private static <T> T newest(Map<T, ?> byId) {
        return List.copyOf(byId.keySet()).get(byId.size() - 1);
    }

    private static <T> T pick(Random random, List<T> from) {
        return from.get(random.nextInt(from.size()));
    }

    private static Duration between(Random random, Duration min, Duration max) {
        return min.plusMillis(random.nextInt((int) (max.toMillis() - min.toMillis()) + 1));
    }
}
