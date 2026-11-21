package io.github.seonwkim.example.shardedchat.web;

import io.github.seonwkim.example.shardedchat.chat.ChatService;
import io.github.seonwkim.example.shardedchat.chat.ChatSimulatorActor;
import io.github.seonwkim.example.shardedchat.events.ClusterMembers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/cluster/nodes}: the cluster's nodes as this node sees them, with the chat actors on each. */
@RestController
public class ClusterController {
    /**
     * One node: its number, host and remote port, release, membership status and how many rooms and users
     * run on it. The host is how the other nodes reach it (on Kubernetes, the pod's DNS name).
     */
    public record Node(int node, String host, int port, String version, String status, int actors) {}

    public record Nodes(List<Node> nodes) {}

    private final ClusterMembers members;
    private final ChatService chat;

    public ClusterController(ClusterMembers members, ChatService chat) {
        this.members = members;
        this.chat = chat;
    }

    @GetMapping("/api/cluster/nodes")
    public CompletionStage<Nodes> nodes() {
        CompletionStage<Map<Integer, Integer>> counts =
                chat.state().thenApply(ClusterController::actorsPerNode).exceptionally(e -> Map.of());
        return counts.thenApply(perNode -> new Nodes(members.members().stream()
                .map(m -> new Node(
                        m.node(), m.host(), m.port(), m.version(), m.status(), perNode.getOrDefault(m.node(), 0)))
                .toList()));
    }

    private static Map<Integer, Integer> actorsPerNode(ChatSimulatorActor.ChatState state) {
        Map<Integer, Integer> counts = new HashMap<>();
        Stream.concat(
                        state.rooms().stream().map(ChatSimulatorActor.RoomView::node),
                        state.users().stream().map(ChatSimulatorActor.UserView::node))
                .filter(Objects::nonNull)
                .forEach(node -> counts.merge(node, 1, Integer::sum));
        return counts;
    }
}
