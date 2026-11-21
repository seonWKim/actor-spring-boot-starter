package io.github.seonwkim.example.shardedchat.control;

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * What the blue-green pipeline can do next, from the nodes and the active generation. Pure, so the
 * rules can be tested alone.
 *
 * <ul>
 *   <li><b>Deploy</b> the color that runs no node, once the other color is active: it starts as the
 *       standby, with the next version.
 *   <li><b>Activate</b> the color that is not active, once each of its running nodes is warmed up (at
 *       least {@code minWarm} of them). Activating the old color again is the rollback.
 *   <li><b>Retire</b> the color that is not active: its nodes stop.
 * </ul>
 */
public record Pipeline(@Nullable String deploy, @Nullable String activate, @Nullable String retire) {

    /** A node as the pipeline sees it: its color, and whether it runs and is warmed up. */
    public record Node(String color, boolean live, boolean up) {}

    public static Pipeline of(List<Node> nodes, @Nullable String active, int minWarm) {
        if (active == null) {
            return new Pipeline(null, null, null);
        }
        String other = ControlProperties.other(active);
        List<Node> standby =
                nodes.stream().filter(n -> n.color().equals(other) && n.live()).toList();
        boolean warm = standby.size() >= minWarm && standby.stream().allMatch(Node::up);
        return new Pipeline(standby.isEmpty() ? other : null, warm ? other : null, standby.isEmpty() ? null : other);
    }
}
