package io.github.seonwkim.example.shardedchat.control;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import org.h2.tools.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The store the blue-green generation record lives in: an in-memory H2 database behind a TCP server on
 * a free port. Every chat node of both colors uses it as {@code spring.actor.generation.store=jdbc}, so
 * blue and green read and change one record (the starter creates its {@code actor_generation} table).
 * In production this is the database (or Redis) both deployments already share.
 */
@Component
@ConditionalOnProperty(name = "control.platform", havingValue = "local", matchIfMissing = true)
public class GenerationStoreServer {

    private final Server server;
    private final int port;

    public GenerationStoreServer() throws SQLException, IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = Server.createTcpServer("-tcpPort", String.valueOf(port), "-ifNotExists")
                .start();
    }

    /** The JDBC URL the nodes connect with; the database lives as long as this app. */
    public String url() {
        return "jdbc:h2:tcp://127.0.0.1:" + port + "/mem:generation;DB_CLOSE_DELAY=-1";
    }

    @PreDestroy
    void stop() {
        server.stop();
    }
}
