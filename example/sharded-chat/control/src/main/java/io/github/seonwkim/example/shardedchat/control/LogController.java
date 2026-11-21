package io.github.seonwkim.example.shardedchat.control;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The in-memory logs as plain text: {@code control}, or {@code node-<n>} (that node's latest run). */
@RestController
public class LogController {

    private final ControlLog control;
    private final Nodes nodes;

    public LogController(ControlLog control, Nodes nodes) {
        this.control = control;
        this.nodes = nodes;
    }

    @GetMapping(path = "/api/logs/{source:control|node-\\d+}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> log(@PathVariable String source) {
        if ("control".equals(source)) {
            return text(control.buffer());
        }
        int node = Integer.parseInt(source.substring("node-".length()));
        return nodes.get(node)
                .map(n -> ResponseEntity.ok(String.join("\n", nodes.platform().log(node)) + "\n"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<String> text(LogBuffer buffer) {
        return ResponseEntity.ok(String.join("\n", buffer.lines()) + "\n");
    }
}
