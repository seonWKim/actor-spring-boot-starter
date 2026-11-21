package io.github.seonwkim.actor.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

    /** The database's current time: H2, PostgreSQL and MySQL all run this statement. */
    static Timestamp now(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getTimestamp(1);
        }
    }

    static boolean isDuplicateKey(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith(INTEGRITY_VIOLATION);
    }
}
