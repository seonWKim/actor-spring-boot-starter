package io.github.seonwkim.actor.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** Plain-SQL helpers shared by this module's stores. */
final class Jdbc {

    /** SQLSTATE class of integrity constraint violations, such as a duplicate key. */
    private static final String INTEGRITY_VIOLATION = "23";

    private Jdbc() {}

    /** Work on one connection. */
    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} on a connection from the pool with auto-commit on, then puts the pool's setting
     * back. Each statement of these stores is one atomic change (a conditional update, or an insert the
     * primary key accepts once), so it must commit when it runs: with a pool set to {@code auto-commit:
     * false}, the write would otherwise roll back when the connection goes back to the pool, after the
     * store had already reported it done (a lease two nodes believe they hold).
     */
    static <T> T withConnection(DataSource dataSource, Work<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            try {
                return work.run(connection);
            } finally {
                if (!autoCommit) {
                    connection.setAutoCommit(false);
                }
            }
        }
    }

    /**
     * The database's current time in epoch milliseconds. The database computes it, so it does not depend
     * on any node's clock or time zone: H2 and PostgreSQL take the epoch of {@code CURRENT_TIMESTAMP},
     * MySQL and MariaDB {@code UNIX_TIMESTAMP}. Times in these tables are stored as these numbers, so
     * nodes in different time zones, or a zone with daylight saving, compare them alike.
     */
    static long nowMillis(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String sql = product.contains("mysql") || product.contains("mariadb")
                ? "SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))"
                : "SELECT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getBigDecimal(1).movePointRight(3).longValue();
        }
    }

    static boolean isDuplicateKey(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith(INTEGRITY_VIOLATION);
    }
}
