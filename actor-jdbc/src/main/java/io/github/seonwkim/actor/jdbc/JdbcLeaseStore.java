package io.github.seonwkim.actor.jdbc;

import io.github.seonwkim.actor.spi.LeaseStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A {@link LeaseStore} in the {@code actor_lease} table of the application's {@link DataSource}.
 * Each change is one conditional {@code UPDATE} (or the first {@code INSERT}), so the database
 * decides which of several concurrent acquirers wins. Expiry is measured on the database's clock:
 * each call reads the database's time and compares and extends {@code expires_at} (epoch
 * milliseconds) with it, so the nodes' clocks and time zones need not agree. Each statement times out
 * after {@link #QUERY_TIMEOUT_SECONDS}, so a locked row or a slow database fails the call instead of
 * holding a lease thread. The table is created from {@link JdbcPeerRegistry#SCHEMA_LOCATION}.
 */
public final class JdbcLeaseStore implements LeaseStore {

    private static final String TAKE = "UPDATE actor_lease SET owner = ?, expires_at = ?"
            + " WHERE lease_name = ? AND (owner = ? OR expires_at < ?)";
    private static final String INSERT = "INSERT INTO actor_lease (owner, expires_at, lease_name) VALUES (?, ?, ?)";
    private static final String RENEW =
            "UPDATE actor_lease SET expires_at = ? WHERE lease_name = ? AND owner = ? AND expires_at >= ?";
    private static final String RELEASE = "DELETE FROM actor_lease WHERE lease_name = ? AND owner = ?";
    private static final String HOLDER = "SELECT owner FROM actor_lease WHERE lease_name = ? AND expires_at >= ?";

    /** How long one statement may run: well inside the lease's ttl and Pekko's operation timeout. */
    static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public JdbcLeaseStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean tryAcquire(String lease, String owner, Duration ttl) {
        try {
            return Jdbc.withConnection(dataSource, connection -> acquire(connection, lease, owner, ttl));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not acquire lease " + lease + " in actor_lease", e);
        }
    }

    private static boolean acquire(Connection connection, String lease, String owner, Duration ttl)
            throws SQLException {
        long now = Jdbc.nowMillis(connection);
        long expires = now + ttl.toMillis();
        if (take(connection, lease, owner, now, expires)) {
            return true;
        }
        try (PreparedStatement insert = prepare(connection, INSERT)) {
            insert.setString(1, owner);
            insert.setLong(2, expires);
            insert.setString(3, lease);
            return insert.executeUpdate() == 1;
        } catch (SQLException e) {
            if (!Jdbc.isDuplicateKey(e)) {
                throw e;
            }
            // Another owner inserted it first: it holds the lease, unless that already expired.
            return take(connection, lease, owner, now, expires);
        }
    }

    private static boolean take(Connection connection, String lease, String owner, long now, long expires)
            throws SQLException {
        try (PreparedStatement take = prepare(connection, TAKE)) {
            take.setString(1, owner);
            take.setLong(2, expires);
            take.setString(3, lease);
            take.setString(4, owner);
            take.setLong(5, now);
            return take.executeUpdate() == 1;
        }
    }

    @Override
    public boolean renew(String lease, String owner, Duration ttl) {
        try {
            return Jdbc.withConnection(dataSource, connection -> {
                long now = Jdbc.nowMillis(connection);
                try (PreparedStatement renew = prepare(connection, RENEW)) {
                    renew.setLong(1, now + ttl.toMillis());
                    renew.setString(2, lease);
                    renew.setString(3, owner);
                    renew.setLong(4, now);
                    return renew.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not renew lease " + lease + " in actor_lease", e);
        }
    }

    @Override
    public void release(String lease, String owner) {
        try {
            Jdbc.withConnection(dataSource, connection -> {
                try (PreparedStatement release = prepare(connection, RELEASE)) {
                    release.setString(1, lease);
                    release.setString(2, owner);
                    return release.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not release lease " + lease + " in actor_lease", e);
        }
    }

    @Override
    public Optional<String> holder(String lease) {
        try {
            return Jdbc.withConnection(dataSource, connection -> {
                long now = Jdbc.nowMillis(connection);
                try (PreparedStatement holder = prepare(connection, HOLDER)) {
                    holder.setString(1, lease);
                    holder.setLong(2, now);
                    try (ResultSet row = holder.executeQuery()) {
                        return row.next() ? Optional.of(row.getString(1)) : Optional.<String>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read lease " + lease + " from actor_lease", e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }
}
