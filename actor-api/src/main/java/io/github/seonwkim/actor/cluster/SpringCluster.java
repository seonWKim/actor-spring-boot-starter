package io.github.seonwkim.actor.cluster;

import java.util.List;

/**
 * Who this node is and who is in the cluster now, from {@code SpringActorSystem.cluster()}.
 *
 * <pre>{@code
 * ClusterMember me = actorSystem.cluster().self();
 * boolean inZoneA = me.roles().contains("zone-a");
 * List<ClusterMember> up = actorSystem.cluster().members().stream()
 *         .filter(m -> m.status() == MemberStatus.UP)
 *         .toList();
 * }</pre>
 *
 * <p>Each call reads this node's current view; nothing is cached. To react to changes, listen for
 * {@link ClusterEvent}s instead of polling.
 *
 * <p>Outside cluster mode there is one member, this node: {@link #self()} has the node address
 * ({@code SpringActorSystem.nodeAddress()}, usually {@code pekko://<name>} with no host or port), no roles and status {@link MemberStatus#UP}, and
 * {@link #members()} lists only it.
 */
public interface SpringCluster {

    /**
     * This node, with its address, its roles ({@code spring.actor.cluster.roles}) and its status. Until it
     * has joined, its status is not {@link MemberStatus#UP}.
     */
    ClusterMember self();

    /**
     * The members this node sees now, in every status, oldest first. Empty before this node has joined
     * or learned about any member. A node that left is dropped once it is removed.
     */
    List<ClusterMember> members();
}
