package io.github.seonwkim.actor.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.jdbc.JdbcPeerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SchemaScriptInitializerTest {

    private static final int NODES = 8;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final class ActorTables extends SchemaScriptInitializer {
        ActorTables(DataSource dataSource) {
            super(dataSource, DatabaseInitializationMode.ALWAYS, JdbcPeerRegistry.SCHEMA_LOCATION);
        }
    }

    /** Each node has its own pool, as separate JVMs would. */
    private static DataSource node() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void nodesStartingTogetherOnPostgresAllCreateTheTables() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(NODES);
        try {
            List<CompletableFuture<Boolean>> starts = new ArrayList<>();
            for (int i = 0; i < NODES; i++) {
                ActorTables tables = new ActorTables(node());
                starts.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                go.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return tables.initializeDatabase();
                        },
                        pool));
            }
            go.countDown();
            for (CompletableFuture<Boolean> start : starts) {
                assertThat(start.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(new JdbcTemplate(node())
                        .queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'actor_%'",
                                String.class))
                .containsExactlyInAnyOrder("actor_peer", "actor_lease", "actor_generation");
    }
}
