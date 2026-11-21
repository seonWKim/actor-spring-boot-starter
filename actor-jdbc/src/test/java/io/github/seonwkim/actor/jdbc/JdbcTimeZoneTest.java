package io.github.seonwkim.actor.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seonwkim.actor.spi.Peer;
import java.time.Duration;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Nodes in different time zones read each other's entries alike: a lease one node holds is still held
 * for a node nine hours ahead, and a peer it registered is still listed.
 */
class JdbcTimeZoneTest {

    private static final Duration TTL = Duration.ofMinutes(2);

    abstract static class Contract {

        abstract DataSource dataSource();

        /** Runs {@code work} as a node whose JVM is in {@code zone}. */
        private static <T> T inZone(String zone, java.util.function.Supplier<T> work) {
            TimeZone before = TimeZone.getDefault();
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            try {
                return work.get();
            } finally {
                TimeZone.setDefault(before);
            }
        }

        @Test
        void aLeaseHeldInOneZoneIsNotFreeInAnother() {
            JdbcLeaseStore store = new JdbcLeaseStore(dataSource());
            String lease = "tz-" + UUID.randomUUID();

            assertThat(inZone("UTC", () -> store.tryAcquire(lease, "node-utc", TTL)))
                    .isTrue();

            assertThat(inZone("Asia/Seoul", () -> store.tryAcquire(lease, "node-seoul", TTL)))
                    .isFalse();
            assertThat(inZone("Asia/Seoul", () -> store.holder(lease))).contains("node-utc");
        }

        @Test
        void aPeerRegisteredInOneZoneIsListedInAnother() {
            JdbcPeerRegistry registry = new JdbcPeerRegistry(dataSource());
            String service = "tz-" + UUID.randomUUID();
            Peer peer = new Peer(service, "node-seoul", "10.0.0.9", 25520);

            inZone("Asia/Seoul", () -> {
                registry.register(peer);
                return null;
            });

            assertThat(inZone("UTC", () -> registry.peers(service, Duration.ofSeconds(30))))
                    .containsExactly(peer);
        }
    }

    @Nested
    class H2 extends Contract {
        private final DataSource dataSource = TestDatabases.h2();

        @Override
        DataSource dataSource() {
            return dataSource;
        }
    }

    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    class PostgreSql extends Contract {
        @Override
        DataSource dataSource() {
            return TestDatabases.postgres();
        }
    }

    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    class MySql extends Contract {
        @Override
        DataSource dataSource() {
            return TestDatabases.mysql();
        }
    }
}
