package io.github.seonwkim.example.shardedchat.events;

import io.github.seonwkim.actor.cluster.ClusterEvent;
import io.github.seonwkim.actor.cluster.ClusterMember;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * This node's view of the cluster's members, from the starter's {@link ClusterEvent}s (Spring
 * application events; the first ones replay the current state), so the page hears of each change at once.
 * A member's node number is its role {@code node-<n>} and its release its role {@code version-<v>}
 * ({@code spring.actor.cluster.roles}, set from {@code sharded-chat.node} and {@code sharded-chat.version}). Each change also goes to this node's page as a {@code member} event.
 */
@Component
public class ClusterMembers {
    /** One node: its number, host and remote port, release, and membership status. */
    public record Member(int node, String host, int port, String version, String status) {}

    /** The role that numbers a node: {@code node-1}, {@code node-2}, ... */
    public static final String NODE_ROLE = "node-";

    /** The role that names a node's release: {@code version-v2}. */
    public static final String VERSION_ROLE = "version-";

    private final ChatEvents events;
    private final Map<Integer, Member> members = new TreeMap<>();

    public ClusterMembers(ChatEvents events) {
        this.events = events;
    }

    @EventListener
    public void on(ClusterEvent event) {
        switch (event) {
            case ClusterEvent.MemberJoined e -> set(e.member(), "joining");
            case ClusterEvent.MemberWeaklyUp e -> set(e.member(), "joining");
            case ClusterEvent.MemberUp e -> set(e.member(), "up");
            case ClusterEvent.MemberLeft e -> set(e.member(), "leaving");
            case ClusterEvent.MemberExited e -> set(e.member(), "leaving");
            case ClusterEvent.MemberDowned e -> set(e.member(), "unreachable");
            case ClusterEvent.UnreachableMember e -> set(e.member(), "unreachable");
            case ClusterEvent.ReachableMember e -> set(e.member(), "up");
            case ClusterEvent.MemberRemoved e -> set(e.member(), "removed");
            case ClusterEvent.LeaderChanged e -> {}
        }
    }

    public synchronized List<Member> members() {
        return List.copyOf(members.values());
    }

    private void set(ClusterMember member, String status) {
        OptionalInt node = nodeOf(member);
        if (node.isEmpty()) {
            return;
        }
        int n = node.getAsInt();
        synchronized (this) {
            Member before = members.get(n);
            if (before != null && before.status().equals(status)) {
                return;
            }
            members.put(n, new Member(n, member.host().orElse(""), member.port().orElse(0), versionOf(member), status));
        }
        events.emit(ChatEvent.member(n, status));
    }

    /** The release in a member's {@code version-<v>} role; empty for a member without one. */
    public static String versionOf(ClusterMember member) {
        return member.roles().stream()
                .filter(role -> role.startsWith(VERSION_ROLE))
                .map(role -> role.substring(VERSION_ROLE.length()))
                .findFirst()
                .orElse("");
    }

    /** The node number in a member's {@code node-<n>} role; empty for a member without one. */
    public static OptionalInt nodeOf(ClusterMember member) {
        return member.roles().stream()
                .filter(role -> role.startsWith(NODE_ROLE))
                .mapToInt(role -> Integer.parseInt(role.substring(NODE_ROLE.length())))
                .findFirst();
    }
}
