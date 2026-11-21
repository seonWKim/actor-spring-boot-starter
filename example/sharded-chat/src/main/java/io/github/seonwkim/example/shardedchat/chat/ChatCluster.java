package io.github.seonwkim.example.shardedchat.chat;

import io.github.seonwkim.actor.SpringActorHandle;
import io.github.seonwkim.actor.SpringActorSystem;
import io.github.seonwkim.actor.cluster.ClusterEvent;
import io.github.seonwkim.actor.cluster.MemberStatus;
import io.github.seonwkim.actor.shard.SpringShardedActorHandle;
import io.github.seonwkim.example.shardedchat.events.ClusterMembers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * How this node reaches the chat's actors.
 *
 * <ul>
 *   <li>A room or a user by its id: {@code sharded(...).withId(id).get()}. The handle is cheap and the
 *       entity may run on any node; the cluster starts it on its first message.
 *   <li>The simulator: a cluster singleton. Each node gets its handle with {@code singletonHandle()},
 *       usable at once: the cluster runs one simulator, on the oldest node, and the handle is this node's
 *       proxy to it, which follows it when it moves and keeps what is sent before it runs.
 * </ul>
 */
@Component
public class ChatCluster {
    public static final String SIMULATOR_ID = "chat-simulator";

    private final SpringActorSystem actorSystem;
    private final int node;
    private volatile SpringActorHandle<ChatSimulatorActor.Command> simulator;

    public ChatCluster(SpringActorSystem actorSystem, @Value("${sharded-chat.node:1}") int node) {
        this.actorSystem = actorSystem;
        this.node = node;
    }

    /** This node's number (1, 2, 3, ...), as {@code bin/start} numbers them. */
    public int node() {
        return node;
    }

    /** How many members this node sees Up now. */
    public long upMembers() {
        return actorSystem.cluster().members().stream()
                .filter(m -> m.status() == MemberStatus.UP)
                .count();
    }

    public SpringShardedActorHandle<ChatRoomActor.Command> room(String roomId) {
        return actorSystem.sharded(ChatRoomActor.class).withId(roomId).get();
    }

    public SpringShardedActorHandle<ChatUserActor.Command> user(String userId) {
        return actorSystem.sharded(ChatUserActor.class).withId(userId).get();
    }

    /** The proxy to the simulator; the first call starts the singleton on this node's side. */
    public SpringActorHandle<ChatSimulatorActor.Command> simulator() {
        SpringActorHandle<ChatSimulatorActor.Command> handle = simulator;
        if (handle == null) {
            synchronized (this) {
                if (simulator == null) {
                    simulator = actorSystem
                            .actor(ChatSimulatorActor.class)
                            .withId(SIMULATOR_ID)
                            .singletonHandle();
                }
                handle = simulator;
            }
        }
        return handle;
    }

    /** A node left: the simulator restarts its rooms and users elsewhere (every node tells it; once is enough). */
    @EventListener
    public void on(ClusterEvent.MemberRemoved event) {
        ClusterMembers.nodeOf(event.member())
                .ifPresent(lost -> simulator().tell(new ChatSimulatorActor.NodeLost(lost)));
    }

    /** Starts the simulator once the application is ready, so the run begins without waiting for a request. */
    @EventListener(ApplicationReadyEvent.class)
    public void spawnSimulator() {
        simulator();
    }
}
